package com.finsight.service;

import com.finsight.model.Budget;
import com.finsight.model.Expense;
import com.finsight.model.Notification;
import com.finsight.model.ReportJob;
import com.finsight.model.User;
import com.finsight.repository.AuditLogRepository;
import com.finsight.repository.BudgetRepository;
import com.finsight.repository.ExpenseRepository;
import com.finsight.repository.NotificationRepository;
import com.finsight.repository.ReportJobRepository;
import com.finsight.repository.UserRepository;
import com.finsight.queue.NotificationQueueManager;
import com.finsight.dto.request.LoginRequest;
import com.finsight.exception.InvalidCredentialsException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.TimeUnit;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@com.finsight.security.WithMockCustomUser(roles = "FINANCE_ADMIN")
@ActiveProfiles("test")
public class DatabaseConcurrencyTest {

    @Autowired
    private AuthService authService;

    @Autowired
    private ExpenseService expenseService;
    
    @Autowired
    private BudgetService budgetService;

    @Autowired
    private NotificationQueueManager notificationQueueManager;
    
    @Autowired
    private ReportExportService reportExportService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private org.springframework.security.crypto.password.PasswordEncoder passwordEncoder;
    
    @Autowired
    private ExpenseRepository expenseRepository;
    
    @Autowired
    private BudgetRepository budgetRepository;
    
    @Autowired
    private NotificationRepository notificationRepository;
    
    @Autowired
    private ReportJobRepository reportJobRepository;

    @Autowired
    private AuditLogRepository auditLogRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    private User testUser;

    @BeforeEach
    void setUp() {
        auditLogRepository.deleteAll();
        expenseRepository.deleteAll();
        budgetRepository.deleteAll();
        notificationRepository.deleteAll();
        reportJobRepository.deleteAll();
        userRepository.deleteAll();

        User user = new User();
        user.setName("Concurrency Test User");
        user.setEmail("concurrency@example.com");
        // encode a password for auth test
        user.setPassword(passwordEncoder.encode("correct-password")); // actual hash
        user.setRole(com.finsight.model.Role.EMPLOYEE);
        testUser = userRepository.save(user);
    }

    // Test 1 — Failed login concurrency
    @Test
    void testFailedLoginConcurrency() throws InterruptedException {
        int threadCount = 10;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threadCount);

        java.util.concurrent.ConcurrentLinkedQueue<Exception> exceptions = new java.util.concurrent.ConcurrentLinkedQueue<>();
        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    latch.await();
                    LoginRequest req = new LoginRequest();
                    req.setEmail(testUser.getEmail());
                    req.setPassword("wrong-password");
                    authService.login(req);
                } catch (InvalidCredentialsException | com.finsight.exception.AccountLockedException e) {
                    // expected
                } catch (Exception e) {
                    exceptions.add(e);
                } finally {
                    done.countDown();
                }
            });
        }

        latch.countDown(); // start threads
        assertTrue(done.await(5, TimeUnit.SECONDS));

        User updatedUser = userRepository.findById(testUser.getUserId()).orElseThrow();
        // Since we explicitly lock the row, the first 5 attempts will increment the counter to 5 and lock the account.
        // The remaining 5 attempts will throw AccountLockedException before incrementing.
        assertEquals(5, updatedUser.getFailedLoginAttempts(), "Failed login attempts should cap at 5 due to lockout");
        assertNotNull(updatedUser.getLockedUntil(), "Account should be locked");
        assertTrue(exceptions.isEmpty(), "Test threw unexpected exceptions: " + exceptions);
        executor.shutdown();
    }

    // Test 2 — Expense optimistic locking
    @Test
    void testExpenseOptimisticLocking() throws InterruptedException {
        Expense expense = new Expense();
        expense.setCreatedBy(testUser);
        expense.setAmount(new BigDecimal("100.00"));
        expense.setCategory(com.finsight.model.ExpenseCategory.OTHER);
        expense.setExpenseDate(LocalDate.now());
        expense.setDescription("Initial");
        Expense savedExpense = expenseRepository.save(expense);

        int threadCount = 2;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threadCount);

        java.util.concurrent.ConcurrentLinkedQueue<Exception> exceptions = new java.util.concurrent.ConcurrentLinkedQueue<>();
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger lockFailureCount = new AtomicInteger(0);

        for (int i = 0; i < threadCount; i++) {
            final int index = i;
            executor.submit(() -> {
                try {
                    latch.await();
                    transactionTemplate.executeWithoutResult(status -> {
                        // Read current state inside transaction
                        Expense r = expenseRepository.findById(savedExpense.getExpenseId()).orElseThrow();
                        // Sleep slightly to force overlap
                        try { Thread.sleep(100); } catch (Exception ignored) {}
                        r.setDescription("Updated by thread " + index);
                        expenseRepository.saveAndFlush(r); // Force flush to trigger version check
                    });
                    successCount.incrementAndGet();
                } catch (ObjectOptimisticLockingFailureException e) {
                    lockFailureCount.incrementAndGet();
                } catch (Exception e) {
                    // Could be Spring's translation of optimistic locking exception
                    if (e.getCause() instanceof org.hibernate.StaleObjectStateException) {
                        lockFailureCount.incrementAndGet();
                    } else {
                        e.printStackTrace();
                    }
                } finally {
                    done.countDown();
                }
            });
        }

        latch.countDown();
        assertTrue(done.await(5, TimeUnit.SECONDS));

        assertEquals(1, successCount.get(), "Only one update should succeed");
        assertEquals(1, lockFailureCount.get(), "One update should fail with OptimisticLockingFailureException");
        assertTrue(exceptions.isEmpty(), "Test threw unexpected exceptions: " + exceptions);
        executor.shutdown();
    }

    // Test 3 — Budget alert race
    @Test
    void testBudgetAlertRace() throws InterruptedException {
        Budget budget = new Budget();
        budget.setCategory(com.finsight.model.ExpenseCategory.MEALS);
        budget.setMonthYear("2026-08");
        budget.setBudgetAmount(new BigDecimal("100.00"));
        budget.setCreatedBy(testUser);
        budget.setAlertSent(false);
        budgetRepository.save(budget);

        // Pre-fill to 150 (exceeds budget of 100)
        Expense r1 = new Expense();
        r1.setCreatedBy(testUser);
        r1.setAmount(new BigDecimal("150.00"));
        r1.setCategory(com.finsight.model.ExpenseCategory.MEALS);
        r1.setExpenseDate(LocalDate.of(2026, 8, 10));
        r1.setStatus(com.finsight.model.ExpenseStatus.APPROVED);
        expenseRepository.save(r1);

        int threadCount = 2;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threadCount);
        java.util.concurrent.ConcurrentLinkedQueue<Exception> exceptions = new java.util.concurrent.ConcurrentLinkedQueue<>();
        final org.springframework.security.core.context.SecurityContext ctx = org.springframework.security.core.context.SecurityContextHolder.getContext();

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    org.springframework.security.core.context.SecurityContextHolder.setContext(ctx);
                    latch.await();
                    transactionTemplate.executeWithoutResult(status -> {
                        budgetService.checkBudgetExceededAfterRecord(com.finsight.model.ExpenseCategory.MEALS, "2026-08", testUser.getUserId());
                    });
                } catch (Exception e) {
                    exceptions.add(e);
                } finally {
                    done.countDown();
                }
            });
        }

        latch.countDown();
        assertTrue(done.await(5, TimeUnit.SECONDS));

        Budget updatedBudget = budgetRepository.findById(budget.getBudgetId()).orElseThrow();
        assertTrue(updatedBudget.isAlertSent(), "Alert should be sent");

        List<Notification> notifications = notificationRepository.findAll();
        // Since we enforced atomic updates on budget alert flag, we should only have EXACTLY 1 notification created
        assertEquals(1, notifications.size(), "Only ONE budget notification should have been generated despite 2 concurrent limit breaks");
        assertTrue(exceptions.isEmpty(), "Test threw unexpected exceptions: " + exceptions);
        executor.shutdown();
    }

    // Test 4 — Budget notification failure (Atomicity)
    @Test
    void testBudgetNotificationFailureAtomicity() {
        Budget budget = new Budget();
        budget.setCategory(com.finsight.model.ExpenseCategory.TRAVEL);
        budget.setMonthYear("2026-08");
        budget.setBudgetAmount(new BigDecimal("100.00"));
        budget.setCreatedBy(testUser);
        budget.setAlertSent(false);
        budgetRepository.save(budget);
        
        // This simulates a forced database failure AFTER isAlertSent is set but DURING notification insertion
        // We will just artificially cause an exception inside a transaction to ensure rollback works.
        try {
            transactionTemplate.executeWithoutResult(status -> {
                int updated = budgetRepository.markAlertSentIfFalse(budget.getBudgetId());
                assertEquals(1, updated);
                
                // Simulate notification insert failing by throwing runtime exception
                throw new RuntimeException("Simulated DB Insert Failure");
            });
        } catch (RuntimeException e) {
            // Expected
        }
        
        Budget rollbackBudget = budgetRepository.findById(budget.getBudgetId()).orElseThrow();
        assertFalse(rollbackBudget.isAlertSent(), "The isAlertSent flag MUST rollback if the parent transaction (notification insert) fails");
    }

    // Test 5 — Notification duplicate claim
    @Test
    void testNotificationDuplicateClaim() throws InterruptedException {
        Notification n = new Notification();
        n.setType("TEST_ALERT");
        n.setUserId(testUser);
        n.setPayload("payload");
        n.setStatus("PENDING");
        n = notificationRepository.save(n);
        
        Long notifId = n.getNotificationId();
        
        int threadCount = 2;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threadCount);
        
        java.util.concurrent.ConcurrentLinkedQueue<Exception> exceptions = new java.util.concurrent.ConcurrentLinkedQueue<>();
        AtomicInteger successfulClaims = new AtomicInteger(0);
        
        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    latch.await();
                    // Both threads try to claim at same time
                    boolean claimed = notificationQueueManager.claimNotification(notifId);
                    if (claimed) {
                        successfulClaims.incrementAndGet();
                    }
                } catch (Exception e) {
                    exceptions.add(e);
                } finally {
                    done.countDown();
                }
            });
        }
        
        latch.countDown();
        assertTrue(done.await(5, TimeUnit.SECONDS));
        
        assertEquals(1, successfulClaims.get(), "Only exactly ONE worker should successfully claim the notification");
        assertTrue(exceptions.isEmpty(), "Test threw unexpected exceptions: " + exceptions);
        executor.shutdown();
    }

    // Test 6 — Notification retry race
    @Test
    void testNotificationRetryRace() throws InterruptedException {
        Notification n = new Notification();
        n.setType("TEST_ALERT");
        n.setUserId(testUser);
        n.setPayload("payload");
        n.setStatus("PROCESSING");
        n.setRetryCount(1);
        n.setMaxRetries(3);
        n = notificationRepository.save(n);
        
        Long notifId = n.getNotificationId();
        
        int threadCount = 2;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threadCount);
        
        java.util.concurrent.ConcurrentLinkedQueue<Exception> exceptions = new java.util.concurrent.ConcurrentLinkedQueue<>();
        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    latch.await();
                    // Assume both workers think they are processing it and both fail it
                    notificationQueueManager.handleFailure(notifId);
                } catch (Exception e) {
                    exceptions.add(e);
                } finally {
                    done.countDown();
                }
            });
        }
        
        latch.countDown();
        assertTrue(done.await(5, TimeUnit.SECONDS));
        
        Notification updated = notificationRepository.findById(notifId).orElseThrow();
        assertEquals(2, updated.getRetryCount(), "Retry count should only increment once because the stale worker's atomic update will return 0 and be ignored");
        assertTrue(exceptions.isEmpty(), "Test threw unexpected exceptions: " + exceptions);
        executor.shutdown();
    }

    // Test 7 — ReportJob duplicate claim
    @Test
    void testReportJobDuplicateClaim() throws InterruptedException {
        ReportJob job = new ReportJob();
        job.setRequestedBy(testUser);
        job.setPeriod("2026-08");
        job.setStatus("PENDING");
        job = reportJobRepository.save(job);
        
        Long jobId = job.getJobId();
        
        int threadCount = 2;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threadCount);
        
        java.util.concurrent.ConcurrentLinkedQueue<Exception> exceptions = new java.util.concurrent.ConcurrentLinkedQueue<>();
        AtomicInteger successfulClaims = new AtomicInteger(0);
        
        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    latch.await();
                    boolean claimed = reportExportService.claimReportJob(jobId);
                    if (claimed) {
                        successfulClaims.incrementAndGet();
                    }
                } catch (Exception e) {
                    exceptions.add(e);
                } finally {
                    done.countDown();
                }
            });
        }
        
        latch.countDown();
        assertTrue(done.await(5, TimeUnit.SECONDS));
        
        assertEquals(1, successfulClaims.get(), "Only exactly ONE worker should successfully claim the report job");
        assertTrue(exceptions.isEmpty(), "Test threw unexpected exceptions: " + exceptions);
        executor.shutdown();
    }

    // Test 8 — Stale ReportJob worker
    @Test
    void testStaleReportJobWorker() {
        ReportJob job = new ReportJob();
        job.setRequestedBy(testUser);
        job.setPeriod("2026-08");
        job.setStatus("PROCESSING");
        job = reportJobRepository.save(job);
        
        Long jobId = job.getJobId();
        
        // Simulating worker 1 finishing and marking it COMPLETED
        int updated = reportExportService.updateJobState(jobId, "COMPLETED", LocalDateTime.now(), "file1.csv", null);
        assertEquals(1, updated);
        
        // Simulating worker 2 (stale) waking up and trying to mark it FAILED
        int updatedStale = reportExportService.updateJobState(jobId, "FAILED", LocalDateTime.now(), null, "Some reason");
        assertEquals(0, updatedStale, "Stale worker should not be able to update a job that is no longer PROCESSING");
        
        ReportJob dbJob = reportJobRepository.findById(jobId).orElseThrow();
        assertEquals("COMPLETED", dbJob.getStatus(), "State should remain COMPLETED");
        assertEquals("file1.csv", dbJob.getFilePath());
    }

    // Test 9 — Concurrent Idempotency
    @Test
    void testConcurrentIdempotentCreate() throws InterruptedException {
        int threadCount = 10;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threadCount);

        java.util.concurrent.ConcurrentLinkedQueue<Exception> exceptions = new java.util.concurrent.ConcurrentLinkedQueue<>();
        AtomicInteger successfulResponses = new AtomicInteger(0);
        String idempotencyKey = "CONCURRENT_KEY";
        final org.springframework.security.core.context.SecurityContext ctx = org.springframework.security.core.context.SecurityContextHolder.getContext();

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    org.springframework.security.core.context.SecurityContextHolder.setContext(ctx);
                    latch.await();
                    com.finsight.dto.request.CreateExpenseRequest req = new com.finsight.dto.request.CreateExpenseRequest();
                    req.setAmount(new BigDecimal("150.00"));
                    req.setCategory(com.finsight.model.ExpenseCategory.OTHER);
                    req.setExpenseDate(LocalDate.of(2026, 8, 18));
                                        com.finsight.dto.response.ExpenseResponse res = expenseService.createExpense(req, idempotencyKey, testUser.getUserId());
                    if (res != null && res.getExpenseId() != null) {
                        successfulResponses.incrementAndGet();
                    }
                } catch (Exception e) {
                    exceptions.add(e);
                } finally {
                    done.countDown();
                }
            });
        }

        latch.countDown();
        assertTrue(done.await(5, TimeUnit.SECONDS));

        assertEquals(10, successfulResponses.get(), "All 10 requests should return a valid response (1 normal, 9 recovered via idempotency catch block). Exceptions: " + exceptions);

        long expenseCount = expenseRepository.count();
        assertEquals(1, expenseCount, "Exactly ONE expense should be created in the database");
        assertTrue(exceptions.isEmpty(), "Test threw unexpected exceptions: " + exceptions);
        executor.shutdown();
    }
    // Test 10 — Notification Workers (Test D)
    @Test
    void testNotificationWorkersConcurrency() throws InterruptedException {
        int notificationCount = 50;
        CountDownLatch latch = new CountDownLatch(1);
        
        // Temporarily pause the workers by not offering to the queue yet, or we can just save them directly to DB as PENDING
        // and then run recoverNotifications to dump them into the queue.
        for (int i = 0; i < notificationCount; i++) {
            Notification n = new Notification();
        n.setType("TEST_ALERT");
            n.setUserId(testUser);
            n.setPayload("payload_" + i);
            n.setStatus("PENDING");
            n.setRetryCount(0);
            n.setMaxRetries(3);
            notificationRepository.save(n);
        }
        
        // trigger recovery which adds to queue
        notificationQueueManager.pollReadyNotifications();
        
        // Wait for workers to process. We can poll the DB until all are SENT or FAILED, or timeout.
        org.awaitility.Awaitility.await()
            .atMost(15, TimeUnit.SECONDS)
            .pollInterval(100, TimeUnit.MILLISECONDS)
            .until(() -> {
                long pending = notificationRepository.countByStatus("PENDING");
                long processing = notificationRepository.countByStatus("PROCESSING");
                return pending == 0 && processing == 0;
            });
            
        List<Notification> finalStates = notificationRepository.findAll();
        long sentOrFailed = finalStates.stream()
            .filter(n -> n.getStatus().equals("SENT") || n.getStatus().equals("FAILED") || n.getStatus().equals("DEAD_LETTER"))
            .count();
            
        assertEquals(notificationCount, sentOrFailed, "All notifications must reach a terminal state or waiting-for-retry (failed) state, none lost");
        
        // Verify no invalid transitions (e.g. negative retry counts or exceeding max)
        for (Notification n : finalStates) {
            assertTrue(n.getRetryCount() >= 0 && n.getRetryCount() <= n.getMaxRetries() + 1, "Retry count should be valid");
            assertNotNull(n.getStatus(), "Status should not be null");
            assertNotEquals("PROCESSING", n.getStatus(), "No task should be stuck in PROCESSING");
        }
    }

    // Test 11 - Approval/Rejection Concurrency
    @Test
    void testApproveRejectConcurrency() throws InterruptedException {
        // Create an expense
        Expense expense = new Expense();
        expense.setCreatedBy(testUser);
        expense.setAmount(new BigDecimal("500.00"));
        expense.setCategory(com.finsight.model.ExpenseCategory.TRAVEL);
        expense.setExpenseDate(LocalDate.now());
        expense.setStatus(com.finsight.model.ExpenseStatus.PENDING_APPROVAL);
        Expense savedExpense = expenseRepository.save(expense);
        
        // Create a manager
        User manager = new User();
        manager.setName("Manager");
        manager.setEmail("manager@example.com");
        manager.setPassword(passwordEncoder.encode("manager-pass"));
        manager.setRole(com.finsight.model.Role.MANAGER);
        User savedManager = userRepository.save(manager);
        
        testUser.setManager(savedManager);
        userRepository.save(testUser);
        
        int threadCount = 2;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threadCount);
        
        java.util.concurrent.ConcurrentLinkedQueue<Exception> concurrencyExceptions = new java.util.concurrent.ConcurrentLinkedQueue<>();
        
        // Thread 1: Approve
        executor.submit(() -> {
            try {
                latch.await();
                expenseService.approveExpense(savedExpense.getExpenseId(), savedManager.getUserId());
            } catch (Exception e) {
                concurrencyExceptions.add(e);
            } finally {
                done.countDown();
            }
        });
        
        // Thread 2: Reject
        executor.submit(() -> {
            try {
                latch.await();
                expenseService.rejectExpense(savedExpense.getExpenseId(), savedManager.getUserId(), "Rejecting");
            } catch (Exception e) {
                concurrencyExceptions.add(e);
            } finally {
                done.countDown();
            }
        });
        
        latch.countDown();
        assertTrue(done.await(5, TimeUnit.SECONDS));
        
        Expense finalState = expenseRepository.findById(savedExpense.getExpenseId()).orElseThrow();
        

        // Exactly one should succeed, one should fail
        assertEquals(1, concurrencyExceptions.size(), "Exactly one transaction should fail due to concurrent modification");
        Exception ex = concurrencyExceptions.peek();
        boolean isValidException = ex instanceof org.springframework.orm.ObjectOptimisticLockingFailureException || ex instanceof com.finsight.exception.InvalidRequestException || ex instanceof IllegalStateException;
        assertTrue(isValidException, "Losing request should throw a concurrency or state exception");
        
        // Ensure final state is either APPROVED or REJECTED
        assertTrue(finalState.getStatus() == com.finsight.model.ExpenseStatus.APPROVED || finalState.getStatus() == com.finsight.model.ExpenseStatus.REJECTED);
        
        // Ensure exactly one audit event for approve/reject
        long auditCount = auditLogRepository.findAll().stream()
            .filter(a -> a.getEntityId().equals(savedExpense.getExpenseId()) && (a.getAction().equals("APPROVE_EXPENSE") || a.getAction().equals("REJECT_EXPENSE")))
            .count();
        assertEquals(1, auditCount, "Exactly one audit event should be created for the final state transition");
        
        // Ensure exactly one notification
        long notifCount = transactionTemplate.execute(status -> 
            notificationRepository.findAll().stream()
                .filter(n -> n.getUserId().getUserId().equals(testUser.getUserId()) && n.getPayload().contains(savedExpense.getExpenseId().toString()))
                .count()
        );
        assertEquals(1, notifCount, "Exactly one notification should be sent to the employee");

        
        executor.shutdown();
    }

}
