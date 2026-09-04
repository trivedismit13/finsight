package com.finsight.queue;

import com.finsight.model.Notification;
import com.finsight.model.User;
import com.finsight.repository.NotificationRepository;
import com.finsight.repository.UserRepository;
import com.finsight.service.NotificationDispatcherService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@com.finsight.security.WithMockCustomUser(roles = "FINANCE_ADMIN")
@ActiveProfiles("test")
public class NotificationIntegrationTest {

    @Autowired
    private NotificationRepository notificationRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private NotificationDispatcherService dispatcherService;

    @Autowired
    private NotificationQueueManager queueManager;

    @org.springframework.boot.test.mock.mockito.MockBean
    private com.finsight.service.EmailProviderService emailProviderService;

    private User testUser;

    @BeforeEach
    void setUp() {
        notificationRepository.deleteAll();
        userRepository.deleteAll();

        testUser = new User();
        testUser.setName("Notif User");
        testUser.setEmail("notif@test.com");
        testUser.setPassword("password");
        testUser.setRole(com.finsight.model.Role.EMPLOYEE);
        testUser = userRepository.save(testUser);
    }

    @Test
    void test1_Persistence_PendingState() {
        Notification n = new Notification();
        n.setType("TEST_ALERT");
        n.setUserId(testUser);
        n.setPayload("payload");
        n.setStatus("PENDING");
        n.setRetryCount(0);
        n.setMaxRetries(3);
        notificationRepository.save(n);
        
        List<Notification> notifications = notificationRepository.findAll();
        assertEquals(1, notifications.size());
        assertEquals("PENDING", notifications.get(0).getStatus());
    }

    @Test
    void test2_SuccessfulDelivery() throws InterruptedException {
        // Enqueue and process
        Notification n = new Notification();
        n.setType("TEST_ALERT");
        n.setUserId(testUser);
        n.setPayload("payload");
        n.setStatus("PENDING");
        n.setRetryCount(0);
        n.setMaxRetries(3);
        n = notificationRepository.save(n);
        
        // Emulate worker success
        queueManager.claimNotification(n.getNotificationId());
        queueManager.markSuccess(n.getNotificationId());
        
        Notification saved = notificationRepository.findById(n.getNotificationId()).get();
        assertEquals("SENT", saved.getStatus());
        assertNotNull(saved.getDeliveredAt());
        assertEquals(0, saved.getRetryCount());
    }

    @Test
    void test3_FailedDelivery_Backoff() {
        Notification n = new Notification();
        n.setType("TEST_ALERT");
        n.setUserId(testUser);
        n.setPayload("payload");
        n.setStatus("PENDING");
        n.setRetryCount(0);
        n.setMaxRetries(3);
        n = notificationRepository.save(n);
        
        queueManager.claimNotification(n.getNotificationId());
        queueManager.handleFailure(n.getNotificationId());
        
        Notification saved = notificationRepository.findById(n.getNotificationId()).get();
        assertEquals("FAILED", saved.getStatus());
        assertEquals(1, saved.getRetryCount());
        assertNotNull(saved.getNextAttemptAt());
    }

    @Test
    void test5_DeadLetter() {
        Notification n = new Notification();
        n.setType("TEST_ALERT");
        n.setUserId(testUser);
        n.setPayload("payload");
        n.setStatus("PENDING");
        n.setRetryCount(3); // exhausted retries
        n.setMaxRetries(3);
        n = notificationRepository.save(n);
        
        queueManager.claimNotification(n.getNotificationId());
        queueManager.handleFailure(n.getNotificationId()); // should move to dead letter
        
        Notification saved = notificationRepository.findById(n.getNotificationId()).get();
        assertEquals("DEAD_LETTER", saved.getStatus());
        assertNotNull(saved.getDeadLetterReason());
    }

    @Test
    void test6_SentCannotBeProcessedAgain() {
        Notification n = new Notification();
        n.setType("TEST_ALERT");
        n.setUserId(testUser);
        n.setPayload("payload");
        n.setStatus("SENT");
        n.setRetryCount(0);
        n.setMaxRetries(3);
        n = notificationRepository.save(n);
        
        boolean claimed = queueManager.claimNotification(n.getNotificationId());
        assertFalse(claimed, "Worker should refuse to claim a SENT notification");
    }

    @Test
    void test7_ConcurrentDuplicateProcessing() {
        Notification n = new Notification();
        n.setType("TEST_ALERT");
        n.setUserId(testUser);
        n.setPayload("payload");
        n.setStatus("PENDING");
        n.setRetryCount(0);
        n.setMaxRetries(3);
        n = notificationRepository.save(n);
        
        boolean claim1 = queueManager.claimNotification(n.getNotificationId());
        boolean claim2 = queueManager.claimNotification(n.getNotificationId()); // simultaneous claim
        
        assertTrue(claim1);
        assertFalse(claim2, "Second worker should fail to claim the notification");
    }

    @Test
    void testJitterAndRetryTiming() {
        org.mockito.Mockito.when(emailProviderService.sendEmail(org.mockito.ArgumentMatchers.any()))
            .thenReturn(false); // Force all to fail

        // Enqueue 5 notifications at the exact same time
        Long[] ids = new Long[5];
        for (int i = 0; i < 5; i++) {
            Notification n = new Notification();
        n.setType("TEST_ALERT");
            n.setUserId(testUser);
            n.setPayload("payload " + i);
            n.setStatus("PENDING");
            n.setRetryCount(0);
            n.setMaxRetries(3);
            n = notificationRepository.save(n);
            ids[i] = n.getNotificationId();
        }

        // Process them all (claim + fail + set nextAttemptAt)
        for (int i = 0; i < 5; i++) {
            queueManager.claimNotification(ids[i]);
            queueManager.handleFailure(ids[i]);
        }

        // Fetch them and check nextAttemptAt
        List<Notification> failedNotes = notificationRepository.findAll();
        assertEquals(5, failedNotes.size());

        // We check that not all nextAttemptAt are identically the same millisecond/nanosecond
        long distinctTimestamps = failedNotes.stream()
                .map(Notification::getNextAttemptAt)
                .distinct()
                .count();

        // If jitter is working, it's highly improbable all 5 end up with exactly the same nextAttemptAt down to the nanosecond
        assertTrue(distinctTimestamps > 1, "Jitter should prevent all retries from having the exact same nextAttemptAt timestamp");
    }

    @Test
    void testRecoverNotifications() {
        // We mock email to succeed so they become SENT if processed
        org.mockito.Mockito.when(emailProviderService.sendEmail(org.mockito.ArgumentMatchers.any())).thenReturn(true);

        // PENDING
        Notification n1 = new Notification();
        n1.setType("TEST_ALERT");
        n1.setUserId(testUser); n1.setPayload("p1"); n1.setStatus("PENDING"); n1.setRetryCount(0); n1.setMaxRetries(3);
        n1 = notificationRepository.save(n1);

        // FAILED + nextAttemptAt past
        Notification n2 = new Notification();
        n2.setType("TEST_ALERT");
        n2.setUserId(testUser); n2.setPayload("p2"); n2.setStatus("FAILED"); n2.setRetryCount(1); n2.setMaxRetries(3);
        n2.setNextAttemptAt(LocalDateTime.now().minusMinutes(5));
        n2 = notificationRepository.save(n2);

        // FAILED + future nextAttemptAt (should NOT recover)
        Notification n3 = new Notification();
        n3.setType("TEST_ALERT");
        n3.setUserId(testUser); n3.setPayload("p3"); n3.setStatus("FAILED"); n3.setRetryCount(1); n3.setMaxRetries(3);
        n3.setNextAttemptAt(LocalDateTime.now().plusMinutes(5));
        n3 = notificationRepository.save(n3);

        // SENT (should NOT recover)
        Notification n4 = new Notification();
        n4.setType("TEST_ALERT");
        n4.setUserId(testUser); n4.setPayload("p4"); n4.setStatus("SENT"); n4.setRetryCount(0); n4.setMaxRetries(3);
        n4 = notificationRepository.save(n4);

        // DEAD_LETTER (should NOT recover)
        Notification n5 = new Notification();
        n5.setType("TEST_ALERT");
        n5.setUserId(testUser); n5.setPayload("p5"); n5.setStatus("DEAD_LETTER"); n5.setRetryCount(3); n5.setMaxRetries(3);
        n5 = notificationRepository.save(n5);

        queueManager.pollReadyNotifications();
        
        final Long n1Id = n1.getNotificationId();
        final Long n2Id = n2.getNotificationId();
        
        // Wait for workers to process
        org.awaitility.Awaitility.await().atMost(5, java.util.concurrent.TimeUnit.SECONDS).untilAsserted(() -> {
            assertEquals("SENT", notificationRepository.findById(n1Id).get().getStatus());
            assertEquals("SENT", notificationRepository.findById(n2Id).get().getStatus());
        });

        // Verify others are untouched
        assertEquals("FAILED", notificationRepository.findById(n3.getNotificationId()).get().getStatus());
        assertEquals("SENT", notificationRepository.findById(n4.getNotificationId()).get().getStatus());
        assertEquals("DEAD_LETTER", notificationRepository.findById(n5.getNotificationId()).get().getStatus());
    }

    @Test
    void testDuplicateRecoveryPrevention() {
        Notification n = new Notification();
        n.setType("TEST_ALERT");
        n.setUserId(testUser);
        n.setPayload("payload");
        n.setStatus("PENDING");
        n.setRetryCount(0);
        n.setMaxRetries(3);
        n = notificationRepository.save(n);
        
        // Temporarily stop worker from processing by claiming it manually first?
        // Actually, if we just check that queue.contains works
        // But since workers might pop it instantly, we can mock something to block.
        // Or simply call enqueue directly multiple times and check queue size.
        // Replace queue in manager so workers (which poll the old queue) don't consume our test items
        java.util.concurrent.LinkedBlockingQueue<Long> isolatedQueue = new java.util.concurrent.LinkedBlockingQueue<>(100);
        java.util.concurrent.BlockingQueue<Long> originalQueue = (java.util.concurrent.BlockingQueue<Long>) org.springframework.test.util.ReflectionTestUtils.getField(queueManager, "queue");
        org.springframework.test.util.ReflectionTestUtils.setField(queueManager, "queue", isolatedQueue);
        
        try {
            queueManager.enqueue(9999L);
            queueManager.enqueue(9999L);
            queueManager.enqueue(9999L);
            
            long count = isolatedQueue.stream().filter(id -> id.equals(9999L)).count();
            assertEquals(1, count, "Queue should not contain duplicates");
        } finally {
            org.springframework.test.util.ReflectionTestUtils.setField(queueManager, "queue", originalQueue);
        }
    }

    @Test
    void testCrashRecovery() {
        Notification n = new Notification();
        n.setType("TEST_ALERT");
        n.setUserId(testUser);
        n.setPayload("payload");
        n.setStatus("PROCESSING");
        n.setRetryCount(0);
        n.setMaxRetries(3);
        n.setLastAttemptAt(LocalDateTime.now().minusMinutes(35)); // > 30 minutes stale
        n = notificationRepository.save(n);
        
        queueManager.pollReadyNotifications();
        
        Notification saved = notificationRepository.findById(n.getNotificationId()).get();
        // Since pollReadyNotifications resets it to PENDING and enqueues it, and worker picks it up
        // It might be PENDING or SENT/FAILED depending on worker speed.
        // We can just verify it is NOT PROCESSING anymore.
        assertNotEquals("PROCESSING", saved.getStatus());
    }
}
