package com.finsight.service;
import com.finsight.queue.NotificationQueueManager;
import com.finsight.model.Notification;
import com.finsight.model.User;
import com.finsight.repository.NotificationRepository;
import com.finsight.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class NotificationDispatcherService {
    
    private final NotificationRepository notificationRepository;
    private final UserRepository userRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final com.finsight.config.NotificationConfig config;

    /**
     * Persists a notification for external delivery.
     * 
     * Delivery semantics: AT-LEAST-ONCE. 
     * Due to the possibility of crashes or external system timeouts after successful
     * transmission but before updating the database, notifications may be delivered more than once.
     */
    @org.springframework.transaction.annotation.Transactional
    public void enqueueNotification(Long userId, String type, String payload) {
        User user = userRepository.findById(userId).orElseThrow();
        
        Notification n = new Notification();
        n.setUserId(user);
        n.setType(type);
        n.setPayload(payload);
        n.setStatus("PENDING");
        n.setRetryCount(0);
        n.setMaxRetries(config.getRetry().getMaxRetries());
        
        // Generate correlationId if not present in MDC
        String correlationId = org.slf4j.MDC.get(com.finsight.config.MdcLoggingFilter.CORRELATION_ID_KEY);
        if (correlationId == null) correlationId = java.util.UUID.randomUUID().toString();
        n.setCorrelationId(correlationId);

        
        n = notificationRepository.save(n);

        // Publish event to enqueue in memory AFTER transaction commits
        eventPublisher.publishEvent(new NotificationEnqueuedEvent(n.getNotificationId()));
    }
}
