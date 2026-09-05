package com.finsight.service;

import com.finsight.model.Expense;
import com.finsight.model.Notification;
import com.finsight.model.User;
import com.finsight.repository.AuditLogRepository;
import com.finsight.repository.BudgetRepository;
import com.finsight.repository.ExpenseRepository;
import com.finsight.repository.NotificationRepository;
import com.finsight.repository.UserRepository;
import com.finsight.queue.NotificationQueueManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.annotation.Transactional;

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

@SpringBootTest
@com.finsight.security.WithMockCustomUser(roles = "FINANCE_ADMIN")
@ActiveProfiles("test")
public class FailureInjectionDbTest {

    @Autowired
    private ExpenseService recordService;
    
    @Autowired
    private BudgetService budgetService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ExpenseRepository recordRepository;

    @Autowired
    private BudgetRepository budgetRepository;

    @Autowired
    private NotificationRepository notificationRepository;

    @Autowired
    private AuditLogRepository auditLogRepository;

    @SpyBean
    private AuditLogService auditLogService; // Spy to force failure after financial record insertion
    
    @Autowired
    private NotificationQueueManager notificationQueueManager;

    @Autowired
    private TransactionTemplate transactionTemplate;

    private User testUser;
    private User testManager;

    @BeforeEach
    void setUp() {
        auditLogRepository.deleteAll();
        recordRepository.deleteAll();
        budgetRepository.deleteAll();
        notificationRepository.deleteAll();
        userRepository.deleteAll();

        User manager = new User();
        manager.setName("Manager");
        manager.setEmail("manager@example.com");
        manager.setPassword("password");
        manager.setRole(com.finsight.model.Role.MANAGER);
        testManager = userRepository.save(manager);

        User user = new User();
        user.setName("Failure Test User");
        user.setEmail("failure@example.com");
        user.setPassword("password");
        user.setRole(com.finsight.model.Role.EMPLOYEE);
        user.setManager(testManager);
        testUser = userRepository.save(user);
    }

    // 1. Partial DB Write Rollback (e.g. Audit fails)
    @Test
    void testPartialDbWriteRollback() {
        // Force audit log to throw a RuntimeException, simulating a DB crash AFTER the financial record was inserted but before commit
        Mockito.doThrow(new RuntimeException("Simulated DB failure during audit"))
               .when(auditLogService).record(any(Long.class), any(String.class), any(String.class), any(Long.class), any(String.class));

        com.finsight.dto.request.CreateExpenseRequest req = new com.finsight.dto.request.CreateExpenseRequest();
        req.setAmount(new BigDecimal("100.00"));
        req.setCategory(com.finsight.model.ExpenseCategory.OTHER.name());
        req.setExpenseDate(LocalDate.now());
        req.setIdempotencyKey("PARTIAL_FAIL_KEY");

        // The transaction should completely rollback
        assertThrows(RuntimeException.class, () -> recordService.createExpense(req, testUser.getUserId()));

        // Verify that NO partial state exists (the financial record should be gone)
        long recordCount = recordRepository.count();
        assertEquals(0, recordCount, "Financial record should rollback because audit failed");

        long auditCount = auditLogRepository.count();
        assertEquals(0, auditCount, "Audit log should be absent");
    }

    // 2. AFTER_COMMIT Queue Handoff Failure
    @Test
    @org.springframework.security.test.context.support.WithMockUser(roles = "FINANCE_ADMIN")
    void testAfterCommitQueueFailure() {
        // Force the queue enqueue to fail by filling it up (we'll replace the queue temporarily)
        java.util.concurrent.BlockingQueue<Long> originalQueue = (java.util.concurrent.BlockingQueue<Long>) org.springframework.test.util.ReflectionTestUtils.getField(notificationQueueManager, "queue");
        java.util.concurrent.BlockingQueue<Long> tinyQueue = new java.util.concurrent.LinkedBlockingQueue<>(1);
        tinyQueue.add(9999L); // fill the queue
        org.springframework.test.util.ReflectionTestUtils.setField(notificationQueueManager, "queue", tinyQueue);
        
        try {
            // Create a budget that triggers a notification
            com.finsight.model.Budget budget = new com.finsight.model.Budget();
            budget.setCategory(com.finsight.model.ExpenseCategory.MEALS.name());
            budget.setMonthYear("2026-08");
            budget.setBudgetAmount(new BigDecimal("50.00"));
            budget.setCreatedBy(testUser);
            budget.setAlertSent(false);
            budgetService.createOrUpdateBudget(testUser.getUserId(), budget.getCategory(), budget.getMonthYear(), budget.getBudgetAmount());

            // Trigger notification
            com.finsight.dto.request.CreateExpenseRequest req = new com.finsight.dto.request.CreateExpenseRequest();
            req.setAmount(new BigDecimal("60.00"));
            req.setCategory(com.finsight.model.ExpenseCategory.MEALS.name());
            req.setExpenseDate(LocalDate.of(2026, 8, 1));
            
            try {
                com.finsight.dto.response.ExpenseResponse res = recordService.createExpense(req, testUser.getUserId());
                recordService.submitExpense(res.getExpenseId(), testUser.getUserId());
                recordService.approveExpense(res.getExpenseId(), testManager.getUserId());
            } catch (Exception e) {
            }

            // Verify the durable state remains in DB
            List<Notification> notifications = notificationRepository.findAll();
            assertTrue(notifications.size() > 0, "Notification must be durable in the DB even if the queue crashes");
            assertEquals("PENDING", notifications.get(0).getStatus(), "Notification remains in PENDING state ready for recovery");
        } finally {
            org.springframework.test.util.ReflectionTestUtils.setField(notificationQueueManager, "queue", originalQueue);
        }
    }

    // 3. Queue Saturation (Bounded Queue)
    @Test
    void testBoundedQueueSaturation() {
        // Replace queue with tiny one to simulate bounded saturation
        java.util.concurrent.BlockingQueue<Long> originalQueue = (java.util.concurrent.BlockingQueue<Long>) org.springframework.test.util.ReflectionTestUtils.getField(notificationQueueManager, "queue");
        java.util.concurrent.BlockingQueue<Long> tinyQueue = new java.util.concurrent.LinkedBlockingQueue<>(1);
        tinyQueue.add(9999L); // fill the queue
        org.springframework.test.util.ReflectionTestUtils.setField(notificationQueueManager, "queue", tinyQueue);
        
        try {
            Notification n = new Notification();
        n.setType("TEST_ALERT");
            n.setUserId(testUser);
            n.setChannel("EMAIL");
            n.setPayload("bounded_payload");
            n.setStatus("PENDING");
            n.setRetryCount(0);
            notificationRepository.save(n);
            
            // Manual trigger
            try {
                notificationQueueManager.enqueue(n.getNotificationId());
            } catch(Exception e) {}
            
            Notification dbNotif = notificationRepository.findById(n.getNotificationId()).orElseThrow();
            assertEquals("PENDING", dbNotif.getStatus(), "Dropped in-memory task means DB state stays PENDING for future recovery");
        } finally {
            org.springframework.test.util.ReflectionTestUtils.setField(notificationQueueManager, "queue", originalQueue);
        }
    }
}
