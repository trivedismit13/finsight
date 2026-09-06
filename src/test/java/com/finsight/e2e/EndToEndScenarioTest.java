package com.finsight.e2e;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.finsight.dto.request.CreateExpenseRequest;
import com.finsight.dto.request.LoginRequest;
import com.finsight.model.Notification;
import com.finsight.repository.AuditLogRepository;
import com.finsight.repository.NotificationRepository;
import com.finsight.service.EmailProviderService;
import com.finsight.queue.NotificationQueueManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
public class EndToEndScenarioTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private NotificationRepository notificationRepository;

    @Autowired
    private AuditLogRepository auditLogRepository;

    @Autowired
    private NotificationQueueManager notificationQueueManager;

    @Autowired
    private com.finsight.repository.UserRepository userRepository;
    
    @Autowired
    private org.springframework.security.crypto.password.PasswordEncoder passwordEncoder;

    @MockBean
    private EmailProviderService emailProviderService;

    private String employeeToken;
    private String managerToken;
    private String financeAdminToken;
    
    private Long employeeId;
    private Long managerId;

    @BeforeEach
    void setUp() throws Exception {
        notificationRepository.deleteAll();
        auditLogRepository.deleteAll();
        userRepository.deleteAll();
        
        // Setup hierarchy
        com.finsight.model.User financeAdmin = registerUser("Admin", "admin@finsight.com", "password", "FINANCE_ADMIN");
        com.finsight.model.User manager = registerUser("Manager", "manager@finsight.com", "password", "MANAGER");
        com.finsight.model.User employee = registerUser("Employee", "employee@finsight.com", "password", "EMPLOYEE");
        
        employee.setManager(manager);
        userRepository.save(employee);
        
        employeeId = employee.getUserId();
        managerId = manager.getUserId();
        
        employeeToken = loginUser("employee@finsight.com", "password");
        managerToken = loginUser("manager@finsight.com", "password");
        financeAdminToken = loginUser("admin@finsight.com", "password");
    }

    @Test
    void executeWorkflowScenario() throws Exception {
        // --- Step 1: Employee creates DRAFT expense ---
        CreateExpenseRequest createReq = new CreateExpenseRequest();
        createReq.setAmount(new BigDecimal("100.00"));
        createReq.setCategory(com.finsight.model.ExpenseCategory.MEALS);
        createReq.setExpenseDate(LocalDate.now());
        
        MvcResult res1 = mockMvc.perform(post("/api/expenses")
                .header("Authorization", "Bearer " + employeeToken)
                .header("Idempotency-Key", "idem-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(createReq)))
                .andExpect(status().isCreated())
                .andReturn();
                
        Map<?, ?> data1 = (Map<?, ?>) objectMapper.readValue(res1.getResponse().getContentAsString(), Map.class).get("data");
        Long expenseId = ((Number) data1.get("expenseId")).longValue();
        assertEquals("DRAFT", data1.get("status"));
        
        // --- Step 2: Employee submits expense ---
        mockMvc.perform(post("/api/expenses/" + expenseId + "/submit")
                .header("Authorization", "Bearer " + employeeToken))
                .andExpect(status().isOk());
                
        // --- Step 3: Manager rejects expense ---
        mockMvc.perform(post("/api/expenses/" + expenseId + "/reject")
                .header("Authorization", "Bearer " + managerToken)
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"Missing receipt\"}"))
                .andExpect(status().isOk());
                
        // --- Step 4: Employee edits and resubmits ---
        mockMvc.perform(put("/api/expenses/" + expenseId)
                .header("Authorization", "Bearer " + employeeToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"amount\":120.00,\"category\":\"MEALS\",\"expenseDate\":\"2026-09-06\",\"version\":2}"))
                .andExpect(status().isOk());
                
        mockMvc.perform(post("/api/expenses/" + expenseId + "/submit")
                .header("Authorization", "Bearer " + employeeToken))
                .andExpect(status().isOk());
                
        // --- Step 5: Manager approves ---
        mockMvc.perform(post("/api/expenses/" + expenseId + "/approve")
                .header("Authorization", "Bearer " + managerToken))
                .andExpect(status().isOk());
                
        // --- Step 6: Finance Admin processes ---
        mockMvc.perform(post("/api/expenses/admin/" + expenseId + "/process")
                .header("Authorization", "Bearer " + financeAdminToken))
                .andExpect(status().isOk());
                
        // --- Step 7: Create Budget ---
        String currentMonthYear = LocalDate.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM"));
        mockMvc.perform(post("/api/budgets")
                .header("Authorization", "Bearer " + financeAdminToken)
                .param("category", "MEALS")
                .param("monthYear", currentMonthYear)
                .param("limit", "150.00"))
                .andExpect(status().isOk());
                
        // --- Step 8: Employee crosses budget ---
        CreateExpenseRequest crossReq = new CreateExpenseRequest();
        crossReq.setAmount(new BigDecimal("50.00"));
        crossReq.setCategory(com.finsight.model.ExpenseCategory.MEALS);
        crossReq.setExpenseDate(LocalDate.now());
        
        MvcResult res2 = mockMvc.perform(post("/api/expenses")
                .header("Authorization", "Bearer " + employeeToken)
                .header("Idempotency-Key", "idem-2")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(crossReq)))
                .andExpect(status().isCreated())
                .andReturn();
                
        Map<?, ?> data2 = (Map<?, ?>) objectMapper.readValue(res2.getResponse().getContentAsString(), Map.class).get("data");
        Long expenseId2 = ((Number) data2.get("expenseId")).longValue();
        
        mockMvc.perform(post("/api/expenses/" + expenseId2 + "/submit")
                .header("Authorization", "Bearer " + employeeToken)).andExpect(status().isOk());
        mockMvc.perform(post("/api/expenses/" + expenseId2 + "/approve")
                .header("Authorization", "Bearer " + managerToken)).andExpect(status().isOk());
        mockMvc.perform(post("/api/expenses/admin/" + expenseId2 + "/process")
                .header("Authorization", "Bearer " + financeAdminToken)).andDo(org.springframework.test.web.servlet.result.MockMvcResultHandlers.print()).andExpect(status().isOk());
                
        // Check budget alert notification
        List<Notification> notifs = notificationRepository.findAll();
        boolean foundBudgetAlert = notifs.stream().anyMatch(n -> "BUDGET_ALERT".equals(n.getType()));
        assertTrue(foundBudgetAlert, "Budget alert should be created when processed expense crosses budget limit");
        
        // --- Step 9: Finance Admin views Analytics ---
        mockMvc.perform(get("/api/analytics/company?startDate=" + LocalDate.now().minusDays(1) + "&endDate=" + LocalDate.now().plusDays(1))
                .header("Authorization", "Bearer " + financeAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalExpenses").value(170.0)); // 120 + 50
                
        // --- Step 10: Generate and download report ---
        MvcResult reportRes = mockMvc.perform(post("/api/reports/expense-summary")
                .param("period", currentMonthYear)
                .header("Authorization", "Bearer " + financeAdminToken))
                .andExpect(status().isAccepted())
                .andReturn();
                
        Number jobIdNum = (Number) objectMapper.readValue(reportRes.getResponse().getContentAsString(), Map.class).get("data");
        Long jobId = jobIdNum.longValue();
        
        org.awaitility.Awaitility.await().atMost(30, TimeUnit.SECONDS).until(() -> {
            MvcResult statusRes = mockMvc.perform(get("/api/reports/expense-summary/" + jobId)
                    .header("Authorization", "Bearer " + financeAdminToken))
                    .andReturn();
            return statusRes.getResponse().getContentAsString().contains("\"status\":\"COMPLETED\"");
        });
        
        mockMvc.perform(get("/api/reports/expense-summary/" + jobId + "/download")
                .header("Authorization", "Bearer " + financeAdminToken))
                .andExpect(status().isOk());
                
        // --- Step 11: Verify Audit trail ---
        long auditCount = auditLogRepository.count();
        assertTrue(auditCount >= 8, "Audit trail should have captured all significant actions");
    }

    private com.finsight.model.User registerUser(String name, String email, String password, String role) throws Exception {
        com.finsight.model.User user = new com.finsight.model.User();
        user.setName(name);
        user.setEmail(email);
        user.setPassword(passwordEncoder.encode(password));
        user.setRole(com.finsight.model.Role.valueOf(role));
        return userRepository.save(user);
    }

    private String loginUser(String email, String password) throws Exception {
        LoginRequest req = new LoginRequest();
        req.setEmail(email);
        req.setPassword(password);
        
        MvcResult res = mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andReturn();
                
        String body = res.getResponse().getContentAsString();
        com.finsight.dto.response.ApiResponse<?> apiRes = objectMapper.readValue(body, com.finsight.dto.response.ApiResponse.class);
        Map<?, ?> data = (Map<?, ?>) apiRes.getData();
        return (String) data.get("accessToken");
    }
}
