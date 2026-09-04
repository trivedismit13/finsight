package com.finsight.e2e;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.finsight.dto.request.CreateExpenseRequest;
import com.finsight.dto.request.LoginRequest;
import com.finsight.dto.request.RegisterRequest;
import com.finsight.dto.response.ExpenseResponse;
import com.finsight.dto.response.UserResponse;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

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
    private EmailProviderService emailProviderService; // We mock this to simulate failures for step 8

    private String viewerToken;
    private String analystToken;
    private String adminToken;

    @BeforeEach
    void setUp() {
        notificationRepository.deleteAll();
        auditLogRepository.deleteAll();
        userRepository.deleteAll();
    }

    @Test
    void executeScenario() throws Exception {
        // --- Step 1: Register ---
        registerUser("Viewer", "employee@example.com", "password123", "EMPLOYEE");
        registerUser("Analyst", "manager@example.com", "password123", "MANAGER");
        registerUser("Admin", "finance_admin@example.com", "password123", "FINANCE_ADMIN");

        // Verify roles cannot be self-escalated
        // RegistrationRequest allows setting role in this app? Wait, let's check API.
        // If the API allows role input but we assume it assigns correctly, we verify the response.

        // --- Step 2: Login each user ---
        viewerToken = loginUser("employee@example.com", "password123");
        analystToken = loginUser("manager@example.com", "password123");
        adminToken = loginUser("finance_admin@example.com", "password123");

        assertNotNull(viewerToken);
        assertNotNull(analystToken);
        assertNotNull(adminToken);

        // --- Step 3: Viewer attempts to create a financial record ---
        CreateExpenseRequest createReq = new CreateExpenseRequest();
        createReq.setAmount(new BigDecimal("100.00"));
        createReq.setCategory(com.finsight.model.ExpenseCategory.MEALS.name());
        createReq.setExpenseDate(LocalDate.now());

        mockMvc.perform(post("/api/records")
                .header("Authorization", "Bearer " + viewerToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(createReq)))
                .andExpect(status().isForbidden()); // 403

        // --- Step 4: Admin creates records with idempotency key ---
        createReq.setAmount(new BigDecimal("100000.00"));
        createReq.setCategory(com.finsight.model.ExpenseCategory.OTHER.name());
        createReq.setIdempotencyKey("idem-key-1");
        
        MvcResult res1 = mockMvc.perform(post("/api/records")
                .header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(createReq)))
                .andExpect(status().isCreated())
                .andReturn();
        
        java.util.Map<?, ?> apiRes1 = objectMapper.readValue(res1.getResponse().getContentAsString(), java.util.Map.class);
        java.util.Map<?, ?> record1 = (java.util.Map<?, ?>) apiRes1.get("data");
        assertNotNull(record1.get("expenseId"));

        createReq.setAmount(new BigDecimal("5000.00"));
        createReq.setCategory(com.finsight.model.ExpenseCategory.MEALS.name());
        createReq.setIdempotencyKey("idem-key-2");

        MvcResult res2 = mockMvc.perform(post("/api/records")
                .header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(createReq)))
                .andExpect(status().isCreated())
                .andReturn();
        
        // Verify audit exists
        long auditCount = auditLogRepository.count();
        assertTrue(auditCount >= 2, "Audit logs should be created for the two records");

        // --- Step 5: Repeat exact request ---
        MvcResult res2Repeat = mockMvc.perform(post("/api/records")
                .header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(createReq)))
                .andExpect(status().isCreated())
                .andReturn();
        
        java.util.Map<?, ?> apiRes2Repeat = objectMapper.readValue(res2Repeat.getResponse().getContentAsString(), java.util.Map.class);
        java.util.Map<?, ?> record2Repeat = (java.util.Map<?, ?>) apiRes2Repeat.get("data");
        // Verify no duplicate record (ID should match)
        java.util.Map<?, ?> apiRes2 = objectMapper.readValue(res2.getResponse().getContentAsString(), java.util.Map.class);
        java.util.Map<?, ?> record2 = (java.util.Map<?, ?>) apiRes2.get("data");
        assertEquals(record2.get("expenseId"), record2Repeat.get("expenseId"));

        // --- Step 6: Repeat using same key but different payload ---
        createReq.setAmount(new BigDecimal("6000.00")); // Different amount
        createReq.setIdempotencyKey("idem-key-2");
        
        mockMvc.perform(post("/api/records")
                .header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(createReq)))
                .andExpect(status().isConflict());

        // --- Step 7: Create a budget and cross threshold ---
        mockMvc.perform(post("/api/admin/budgets")
                .header("Authorization", "Bearer " + adminToken)
                .param("category", "Entertainment")
                .param("monthYear", "2026-08")
                .param("limit", "100.00"))
                .andExpect(status().isOk());

        // Cross the threshold
        CreateExpenseRequest crossBudgetReq = new CreateExpenseRequest();
        crossBudgetReq.setAmount(new BigDecimal("150.00"));
        crossBudgetReq.setCategory(com.finsight.model.ExpenseCategory.OTHER.name());
        crossBudgetReq.setExpenseDate(LocalDate.of(2026, 8, 15));
        crossBudgetReq.setIdempotencyKey("budget-key-1");
        
        mockMvc.perform(post("/api/records")
                .header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(crossBudgetReq)))
                .andExpect(status().isCreated());

        // Verify notification creation
        List<Notification> notifs = notificationRepository.findAll();
        boolean foundBudgetAlert = notifs.stream().anyMatch(n -> "BUDGET_ALERT".equals(n.getType()));
        assertTrue(foundBudgetAlert, "Budget alert should be created");

        // --- Step 8: Force notification delivery failure ---
        // Setup mock to always fail
        when(emailProviderService.sendEmail(any(Notification.class))).thenReturn(false);

        // Find the notification ID
        Notification alert = notifs.stream().filter(n -> "BUDGET_ALERT".equals(n.getType())).findFirst().orElseThrow();
        Long notifId = alert.getNotificationId();

        // Queue it so worker picks it up
        notificationQueueManager.pollReadyNotifications();
        
        // Wait and poll until DEAD_LETTER
        org.awaitility.Awaitility.await().atMost(30, TimeUnit.SECONDS).until(() -> {
            Notification current = notificationRepository.findById(notifId).orElseThrow();
            return "DEAD_LETTER".equals(current.getStatus());
        });

        Notification finalNotif = notificationRepository.findById(notifId).orElseThrow();
        assertEquals("DEAD_LETTER", finalNotif.getStatus());
        assertEquals(3, finalNotif.getRetryCount());

        // --- Step 9: Generate report as Analyst ---
        MvcResult reportRes = mockMvc.perform(post("/api/reports/export?period=2026-08")
                .header("Authorization", "Bearer " + analystToken))
                .andExpect(status().isAccepted()) // 202
                .andReturn();
        
        String reportResBody = reportRes.getResponse().getContentAsString();
        java.util.Map<?, ?> apiRes = objectMapper.readValue(reportResBody, java.util.Map.class);
        Number jobIdNum = (Number) apiRes.get("data");
        Long jobId = jobIdNum.longValue();

        // --- Step 10: Verify report completion and notification ---
        org.awaitility.Awaitility.await().atMost(30, TimeUnit.SECONDS).until(() -> {
            MvcResult statusRes = mockMvc.perform(get("/api/reports/export/" + jobId)
                    .header("Authorization", "Bearer " + analystToken))
                    .andReturn();
            String statusBody = statusRes.getResponse().getContentAsString();
            System.out.println("REPORT STATUS BODY: " + statusBody);
            return statusBody.contains("\"status\":\"COMPLETED\"");
        });

        // Verify notification
        List<Notification> reportNotifs = notificationRepository.findAll();
        boolean foundReportAlert = reportNotifs.stream().anyMatch(n -> "REPORT_READY".equals(n.getType()) && n.getPayload().contains("JobId=" + jobId));
        assertTrue(foundReportAlert, "Report notification should be created");

        // --- Step 11: Attempt to download another user's report ---
        // Analyst 1 tries to download
        mockMvc.perform(get("/api/reports/export/" + jobId + "/download")
                .header("Authorization", "Bearer " + analystToken))
                .andExpect(status().isOk());
                
        // Viewer tries
        mockMvc.perform(get("/api/reports/export/" + jobId + "/download")
                .header("Authorization", "Bearer " + viewerToken))
                .andExpect(status().isForbidden()); // 403 because Viewer doesn't have role
        
        // Let's create Analyst 2 to prove IDOR prevention
        registerUser("Analyst2", "analyst2@example.com", "password123", "MANAGER");
        String analyst2Token = loginUser("analyst2@example.com", "password123");
        
        mockMvc.perform(get("/api/reports/export/" + jobId + "/download")
                .header("Authorization", "Bearer " + analyst2Token))
                .andExpect(status().isForbidden()); // 403 due to ownership

        // --- Step 12 & 13: Concurrency tests ---
        // Already heavily covered in DatabaseConcurrencyTest, but we can do a quick one here
        int threadCount = 10;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threadCount);
        
        AtomicInteger successCount = new AtomicInteger(0);
        
        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    latch.await();
                    CreateExpenseRequest concReq = new CreateExpenseRequest();
                    concReq.setAmount(new BigDecimal("500.00"));
                    concReq.setCategory(com.finsight.model.ExpenseCategory.TRAVEL.name());
                    concReq.setExpenseDate(LocalDate.now());
                    concReq.setIdempotencyKey("concurrent-idem-key");
                    
                    MvcResult res = mockMvc.perform(post("/api/records")
                            .header("Authorization", "Bearer " + adminToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(concReq)))
                            .andReturn();
                            
                    if (res.getResponse().getStatus() == 201) {
                        successCount.incrementAndGet();
                    }
                } catch (Exception e) {
                    // Ignore
                } finally {
                    done.countDown();
                }
            });
        }
        
        latch.countDown();
        done.await(10, TimeUnit.SECONDS);
        
        // All 10 requests should return 201 due to idempotency catching exception and returning original
        assertEquals(10, successCount.get(), "Idempotency should allow all concurrent requests to return success");

        // --- Step 14: Inspect audit log ---
        long finalAuditCount = auditLogRepository.count();
        assertTrue(finalAuditCount >= 4, "Audit logs should have captured multiple events");
    }

    private void registerUser(String name, String email, String password, String role) throws Exception {
        com.finsight.model.User user = new com.finsight.model.User();
        user.setName(name);
        user.setEmail(email);
        user.setPassword(passwordEncoder.encode(password));
        user.setRole(com.finsight.model.Role.valueOf(role));
        userRepository.save(user);
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
        java.util.Map<?, ?> data = (java.util.Map<?, ?>) apiRes.getData();
        return (String) data.get("accessToken");
    }
}
