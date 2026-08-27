package com.finsight.queue;

import com.finsight.model.Notification;
import com.finsight.repository.NotificationRepository;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.finsight.service.EmailProviderService;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.*;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Counter;

@Slf4j
@Component
public class NotificationQueueManager {


    private final BlockingQueue<Long> queue;
    private final Executor notificationExecutor;
    private final com.finsight.config.NotificationConfig config;
    private final NotificationRepository notificationRepository;
    private final EmailProviderService emailProviderService;
    private final MeterRegistry meterRegistry;
    
    private final Counter notificationSuccessCounter;
    private final Counter notificationFailureCounter;
    private final Counter notificationDeadLetterCounter;

    public NotificationQueueManager(
            NotificationRepository notificationRepository,
            EmailProviderService emailProviderService,
            MeterRegistry meterRegistry,
            com.finsight.config.NotificationConfig config,
            @org.springframework.beans.factory.annotation.Qualifier("notificationExecutor") Executor notificationExecutor) {
        this.notificationRepository = notificationRepository;
        this.emailProviderService = emailProviderService;
        this.meterRegistry = meterRegistry;
        this.config = config;
        this.notificationExecutor = notificationExecutor;
        this.queue = new LinkedBlockingQueue<>(config.getQueueCapacity());
        
        this.notificationSuccessCounter = meterRegistry.counter("notifications.completed.total");
        this.notificationFailureCounter = meterRegistry.counter("notifications.failed.total");
        this.notificationDeadLetterCounter = meterRegistry.counter("notifications.dead_letter.total");
        
        meterRegistry.gauge("notification.queue.size", this.queue, BlockingQueue::size);
    }
    
    // Self injection to use @Transactional methods properly
    @org.springframework.beans.factory.annotation.Autowired
    @org.springframework.context.annotation.Lazy
    private NotificationQueueManager self;

    @PostConstruct
    public void init() {
        for (int i = 0; i < config.getWorkerPoolSize(); i++) {
            notificationExecutor.execute(this::processQueue);
        }
        log.info("NotificationQueueManager started with {} workers", config.getWorkerPoolSize());
        recoverNotifications();
    }

    @org.springframework.scheduling.annotation.Scheduled(fixedRateString = "${finsight.notification.poll-interval-ms:15000}")
    public void pollReadyNotifications() {
        recoverNotifications();
    }

    @PreDestroy
    public void shutdown() {
        log.info("Shutting down NotificationQueueManager...");
        // ThreadPoolTaskExecutor is managed by Spring and will gracefully shut down based on AsyncConfig
    }

    public void enqueue(Long notificationId) {
        if (queue.contains(notificationId)) {
            return;
        }
        boolean added = queue.offer(notificationId);
        if (!added) {
            log.warn("Notification queue is full, dropping memory task for notificationId={}", notificationId);
        }
    }

    private void recoverNotifications() {
        int capacity = queue.remainingCapacity();
        if (capacity <= 0) return;

        LocalDateTime now = LocalDateTime.now();
        org.springframework.data.domain.Pageable pageable = org.springframework.data.domain.PageRequest.of(0, capacity);
        List<Notification> pendingOrFailed = notificationRepository.findPendingAndReadyFailed(now, pageable);
        for (Notification n : pendingOrFailed) {
            enqueue(n.getNotificationId());
        }
        
        List<Notification> stale = notificationRepository.findStaleProcessing(now.minusMinutes(config.getProcessingTimeoutMinutes()), pageable);
        for (Notification n : stale) {
            log.warn("Recovering stale PROCESSING notification: {}", n.getNotificationId());
            self.resetStaleNotification(n.getNotificationId());
            enqueue(n.getNotificationId());
        }
    }

    @Transactional
    public void resetStaleNotification(Long id) {
        Notification n = notificationRepository.findById(id).orElse(null);
        if (n != null && "PROCESSING".equals(n.getStatus())) {
            n.setStatus("PENDING");
            notificationRepository.save(n);
        }
    }

    private void processQueue() {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                Long notificationId = queue.take();
                processTask(notificationId);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.info("Notification worker interrupted, stopping");
            } catch (Exception e) {
                log.error("Unexpected error in notification worker (worker continues running)", e);
            }
        }
    }

    private void processTask(Long notificationId) {
        try {
            org.slf4j.MDC.put(com.finsight.config.MdcLoggingFilter.TRACE_ID_KEY, java.util.UUID.randomUUID().toString());
            
            // Atomic claim
            boolean claimed = self.claimNotification(notificationId);
            if (!claimed) {
                return;
            }

            Notification notification = notificationRepository.findById(notificationId).orElse(null);
            if (notification == null) return;
            
            if (notification.getCorrelationId() != null) {
                org.slf4j.MDC.put(com.finsight.config.MdcLoggingFilter.CORRELATION_ID_KEY, notification.getCorrelationId());
            }
            if (notification.getUserId() != null) {
                org.slf4j.MDC.put(com.finsight.config.MdcLoggingFilter.USER_ID_KEY, String.valueOf(notification.getUserId().getUserId()));
            }

            log.info("Processing notification {}", notificationId);

            boolean success = emailProviderService.sendEmail(notification);
            if (success) {
                self.markSuccess(notificationId);
            } else {
                self.handleFailure(notificationId);
            }
        } catch (Exception e) {
            log.error("Error processing notification task for id={}: {}", notificationId, e.getMessage(), e);
            self.handleFailure(notificationId);
        } finally {
            org.slf4j.MDC.clear();
        }
    }

    @Transactional
    public boolean claimNotification(Long id) {
        int updated = notificationRepository.claimNotification(id, LocalDateTime.now());
        return updated > 0;
    }

    @Transactional
    public void markSuccess(Long id) {
        int updated = notificationRepository.markSuccess(id, LocalDateTime.now());
        if (updated == 0) {
            log.warn("Stale worker attempted to mark notification {} as SENT", id);
        } else {
            notificationSuccessCounter.increment();
        }
    }

    @Transactional
    public void handleFailure(Long id) {
        Notification n = notificationRepository.findById(id).orElseThrow();
        if (n.getRetryCount() < n.getMaxRetries()) {
            int newRetryCount = n.getRetryCount() + 1;
            long delay = Math.min(config.getRetry().getBaseDelayMs() * (1L << newRetryCount), config.getRetry().getMaxDelayMs())
                    + (long) (Math.random() * config.getRetry().getJitterMs());
            LocalDateTime nextAttemptAt = LocalDateTime.now().plusNanos(delay * 1000000);
            
            int updated = notificationRepository.markFailure(id, newRetryCount, nextAttemptAt);
            if (updated > 0) {
                log.info("Scheduling retry {} for notificationId={} with delay {}ms", newRetryCount, id, delay);
                notificationFailureCounter.increment();
                CompletableFuture.delayedExecutor(delay, TimeUnit.MILLISECONDS).execute(() -> enqueue(id));
            } else {
                log.warn("Stale worker attempted to mark notification {} as FAILED", id);
            }
        } else {
            int updated = notificationRepository.markDeadLetter(id, "Max retries exceeded");
            if (updated > 0) {
                log.error("Notification {} max retries exceeded, marking as DEAD_LETTER", id);
                notificationDeadLetterCounter.increment();
            } else {
                log.warn("Stale worker attempted to mark notification {} as DEAD_LETTER", id);
            }
        }
    }


}
