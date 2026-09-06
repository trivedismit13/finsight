package com.finsight.controller;

import com.finsight.dto.request.CreateExpenseRequest;
import com.finsight.model.Expense;
import com.finsight.model.ExpenseCategory;
import com.finsight.model.ExpenseStatus;
import com.finsight.model.Role;
import com.finsight.model.User;
import com.finsight.repository.ExpenseRepository;
import com.finsight.repository.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
public class ExpenseWorkflowAuthorizationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ExpenseRepository expenseRepository;

    @Autowired
    private com.finsight.repository.AuditLogRepository auditLogRepository;

    private User employeeUser;
    private User managerUser;
    private User adminUser;
    private Expense draftExpense;

    @BeforeEach
    void setUp() {
        auditLogRepository.deleteAll();
        expenseRepository.deleteAll();
        userRepository.deleteAll();

        managerUser = new User();
        managerUser.setName("Manager");
        managerUser.setEmail("manager@finsight.com");
        managerUser.setPassword("password");
        managerUser.setRole(Role.MANAGER);
        managerUser.setActive(true);
        managerUser = userRepository.save(managerUser);

        employeeUser = new User();
        employeeUser.setName("Employee");
        employeeUser.setEmail("employee@finsight.com");
        employeeUser.setPassword("password");
        employeeUser.setRole(Role.EMPLOYEE);
        employeeUser.setActive(true);
        employeeUser.setManager(managerUser);
        employeeUser = userRepository.save(employeeUser);

        adminUser = new User();
        adminUser.setName("Admin");
        adminUser.setEmail("admin@finsight.com");
        adminUser.setPassword("password");
        adminUser.setRole(Role.FINANCE_ADMIN);
        adminUser.setActive(true);
        adminUser = userRepository.save(adminUser);

        draftExpense = new Expense();
        draftExpense.setCreatedBy(employeeUser);
        draftExpense.setAmount(BigDecimal.valueOf(100));
        draftExpense.setCategory(ExpenseCategory.MEALS);
        draftExpense.setExpenseDate(LocalDate.now());
        draftExpense.setStatus(ExpenseStatus.DRAFT);
        draftExpense = expenseRepository.save(draftExpense);
    }

    @Test
    @WithMockUser(roles = "MANAGER", username = "manager@finsight.com")
    void testManagerCreateExpense_403() throws Exception {
        CreateExpenseRequest req = new CreateExpenseRequest();
        req.setAmount(BigDecimal.valueOf(100));
        req.setCategory(ExpenseCategory.MEALS);
        req.setExpenseDate(LocalDate.now());
        
        mockMvc.perform(post("/api/expenses")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "MANAGER", username = "manager@finsight.com")
    void testManagerSubmitExpense_403() throws Exception {
        mockMvc.perform(post("/api/expenses/" + draftExpense.getExpenseId() + "/submit"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "FINANCE_ADMIN", username = "admin@finsight.com")
    void testAdminCreateExpense_403() throws Exception {
        CreateExpenseRequest req = new CreateExpenseRequest();
        req.setAmount(BigDecimal.valueOf(100));
        req.setCategory(ExpenseCategory.MEALS);
        req.setExpenseDate(LocalDate.now());
        
        mockMvc.perform(post("/api/expenses")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "FINANCE_ADMIN", username = "admin@finsight.com")
    void testAdminSubmitExpense_403() throws Exception {
        mockMvc.perform(post("/api/expenses/" + draftExpense.getExpenseId() + "/submit"))
                .andExpect(status().isForbidden());
    }
}
