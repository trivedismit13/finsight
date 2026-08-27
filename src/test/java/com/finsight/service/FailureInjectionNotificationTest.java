package com.finsight.service;

import com.finsight.model.Notification;
import com.finsight.model.User;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import org.awaitility.Awaitility;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;

@SpringBootTest
@com.finsight.security.WithMockCustomUser(roles = "ADMIN")
@ActiveProfiles("test")
public class FailureInjectionNotificationTest {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private NotificationRepository notificationRepository;

    @Autowired
    private NotificationQueueManager notificationQueueManager;

    @MockBean
    private EmailProviderService emailProviderService;

    private User testUser;

    @BeforeEach
    void setUp() {
        notificationRepository.deleteAll();
        userRepository.deleteAll();

        User user = new User();
        user.setName("Notification Failure User");
        user.setEmail("notif_fail@example.com");
        user.setPassword("password");
        user.setRole(com.finsight.model.Role.VIEWER);
        testUser = userRepository.save(user);
    }

    // 1. Provider failure retry -> dead-letter behavior
    @Test
    void testProviderFailureRetryToDeadLetter() throws Exception {
        // Speed up the retry delay so the background worker reaches DEAD_LETTER quickly
        com.finsight.config.NotificationConfig.Retry originalRetry = (com.finsight.config.NotificationConfig.Retry) ReflectionTestUtils.getField(
            ReflectionTestUtils.getField(notificationQueueManager, "config"), "retry");
            
        com.finsight.config.NotificationConfig.Retry fastRetry = new com.finsight.config.NotificationConfig.Retry();
        fastRetry.setBaseDelayMs(10);
        fastRetry.setMaxDelayMs(50);
        fastRetry.setJitterMs(10);
        fastRetry.setMaxRetries(2);
        
        ReflectionTestUtils.setField(ReflectionTestUtils.getField(notificationQueueManager, "config"), "retry", fastRetry);

        try {
            Mockito.doThrow(new RuntimeException("Simulated Email Provider Failure"))
                   .when(emailProviderService).sendEmail(any());

            Notification n = new Notification();
            n.setUserId(testUser);
            n.setType("TEST");
            n.setPayload("payload_1");
            n.setStatus("PENDING");
            n.setRetryCount(0);
            n.setMaxRetries(2); // Initial attempt + 2 retries = 3 attempts total
            n = notificationRepository.save(n);
            
            Long notifId = n.getNotificationId();
            
            notificationQueueManager.enqueue(notifId);
            
            // The background worker will now fail and schedule retries very quickly.
            // Just wait for it to reach DEAD_LETTER.
            Awaitility.await().atMost(Duration.ofSeconds(10)).until(() -> {
                return notificationRepository.findById(notifId).get().getStatus().equals("DEAD_LETTER");
            });
        } finally {
            ReflectionTestUtils.setField(ReflectionTestUtils.getField(notificationQueueManager, "config"), "retry", originalRetry);
        }
    }

    // 2. Worker exception isolation tested
    @Test
    void testWorkerExceptionIsolation() throws Exception {
        // If a worker throws an unexpected error, the task fails but worker shouldn't crash
        // The worker catches Exception inside its loop (we can verify by checking if handleFailure is called)
        
        Mockito.doThrow(new RuntimeException("Simulated Unexpected Infrastructure Exception"))
               .when(emailProviderService).sendEmail(any());

        Notification n = new Notification();
        n.setUserId(testUser);
        n.setType("TEST");
        n.setPayload("payload_isolated");
        n.setStatus("PENDING");
        n.setRetryCount(0);
        n.setMaxRetries(1);
        n = notificationRepository.save(n);
        
        Long notifId = n.getNotificationId();
        
        notificationQueueManager.enqueue(notifId);
        
        Awaitility.await().atMost(Duration.ofSeconds(5)).until(() -> {
            return notificationRepository.findById(notifId).get().getStatus().equals("FAILED");
        });
        
        Notification dbNotif = notificationRepository.findById(notifId).orElseThrow();
        assertEquals("FAILED", dbNotif.getStatus(), "Task failed cleanly without propagating exception to kill worker thread");
    }

    // 3. Stale PROCESSING recovery tested
    @Test
    void testStaleProcessingRecovery() throws Exception {
        Notification n = new Notification();
        n.setUserId(testUser);
        n.setType("TEST");
        n.setPayload("payload_stale");
        n.setStatus("PROCESSING");
        n.setLastAttemptAt(LocalDateTime.now().minusMinutes(10)); // 10 minutes ago, considered stale
        n.setRetryCount(0);
        n.setMaxRetries(3);
        n = notificationRepository.save(n);
        
        // Run scheduled pollReadyNotifications which recovers stale processing
        notificationQueueManager.pollReadyNotifications();
        
        Notification dbNotif = notificationRepository.findById(n.getNotificationId()).orElseThrow();
        assertEquals("PENDING", dbNotif.getStatus(), "Stale task should be moved back to PENDING so it can be retried");
        assertEquals(0, dbNotif.getRetryCount(), "Retry count does not increment on stale reset");
    }
}
