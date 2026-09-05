package com.finsight.service;

import com.finsight.model.Expense;
import com.finsight.model.User;
import com.finsight.repository.AuditLogRepository;
import com.finsight.repository.ExpenseRepository;
import com.finsight.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.test.context.ActiveProfiles;
import com.finsight.dto.request.CreateExpenseRequest;
import com.finsight.model.Role;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@SpringBootTest
@com.finsight.security.WithMockCustomUser(roles = "FINANCE_ADMIN")
@ActiveProfiles("test")
public class AuditIntegrationTest {

    @Autowired
    private ExpenseService expenseService;

    @Autowired
    private ExpenseRepository expenseRepository;

    @Autowired
    private AuditLogRepository auditLogRepository;

    @Autowired
    private UserRepository userRepository;

    @SpyBean
    private AuditLogService auditLogService;

    private User testUser;
    private User testManager;

    @BeforeEach
    void setUp() {
        expenseRepository.deleteAll();
        auditLogRepository.deleteAll();
        userRepository.deleteAll();

        testManager = new User();
        testManager.setName("Manager");
        testManager.setEmail("manager@test.com");
        testManager.setPassword("password");
        testManager.setRole(Role.MANAGER);
        testManager = userRepository.save(testManager);

        testUser = new User();
        testUser.setName("Audit Test User");
        testUser.setEmail("audit@test.com");
        testUser.setPassword("password");
        testUser.setRole(Role.EMPLOYEE);
        testUser.setManager(testManager);
        testUser = userRepository.save(testUser);
    }

    @MockBean
    private BudgetService budgetService;

    @Test
    void testAuditRollback_whenBusinessTransactionFails() {
        CreateExpenseRequest req = new CreateExpenseRequest();
        req.setAmount(new BigDecimal("100.00"));
        req.setCategory(com.finsight.model.ExpenseCategory.MEALS);
        req.setExpenseDate(LocalDate.now());
                com.finsight.dto.response.ExpenseResponse res = expenseService.createExpense(req, "TEST_KEY", testUser.getUserId());
        expenseService.submitExpense(res.getExpenseId(), testUser.getUserId());

        // Clear audit log to measure only approveExpense
        auditLogRepository.deleteAll();

        // Force a failure in the business logic AFTER audit is called in approveExpense
        doThrow(new RuntimeException("Simulated budget error"))
            .when(budgetService).checkBudgetExceededAfterRecord(anyString(), anyString(), anyLong());
        
        try {
            expenseService.approveExpense(res.getExpenseId(), testManager.getUserId());
        } catch (Exception e) {
            // expected
        }

        // Neither state change nor audit should exist
        assertEquals(0, auditLogRepository.count());
    }

    @Test
    void testAuditFailure_preventsBusinessTransactionCommit() {
        CreateExpenseRequest req = new CreateExpenseRequest();
        req.setAmount(new BigDecimal("200.00"));
        req.setCategory(com.finsight.model.ExpenseCategory.TRAVEL);
        req.setExpenseDate(LocalDate.now());
                // Force audit service to throw an exception
        doThrow(new RuntimeException("Audit DB Down")).when(auditLogService)
                .record(any(), anyString(), anyString(), any(), anyString());

        try {
            expenseService.createExpense(req, "TEST_KEY", testUser.getUserId());
        } catch (Exception e) {
            // expected RuntimeException
        }

        // The financial record should not be saved because the audit failed
        assertEquals(0, expenseRepository.count());
        assertEquals(0, auditLogRepository.count());
    }
    @Test
    void testDeleteRecord_auditRollback() {
        Expense record = new Expense();
        record.setAmount(new BigDecimal("100.00"));
        record.setCategory(com.finsight.model.ExpenseCategory.MEALS);
        record.setExpenseDate(LocalDate.now());
        record.setCreatedBy(testUser);
        record = expenseRepository.save(record);
        
        Long expenseId = record.getExpenseId();

        doThrow(new RuntimeException("Audit DB Down")).when(auditLogService)
                .record(any(), eq("DELETE_RECORD"), anyString(), any(), anyString());

        try {
            expenseService.deleteExpense(expenseId, testUser.getUserId());
        } catch (Exception e) {
            // expected RuntimeException
        }

        Expense dbRecord = expenseRepository.findById(expenseId).orElseThrow();
        org.junit.jupiter.api.Assertions.assertFalse(dbRecord.isDeleted());
        assertEquals(0, auditLogRepository.count());
    }
}
