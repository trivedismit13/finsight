package com.finsight.dto.response;

import lombok.Builder;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@Builder
public class NotificationResponse {
    private Long notificationId;
    private Long userId;
    private String type;
    private String channel;
    private String status;
    private int retryCount;
    private int maxRetries;
    private LocalDateTime createdAt;
    private LocalDateTime lastAttemptAt;
    private LocalDateTime deliveredAt;
    private String deadLetterReason;
    
    // Explicitly excluding payload to avoid leaking sensitive report details or tokens
}
