package com.finsight.service;

import com.finsight.repository.NotificationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class NotificationService {

    private final NotificationRepository notificationRepository;

    @Transactional(readOnly = true)
    @PreAuthorize("hasRole('ADMIN')")
    public List<com.finsight.dto.response.NotificationResponse> getDeadLetterQueue() {
        return notificationRepository.findByStatus("DEAD_LETTER")
                .stream()
                .map(n -> com.finsight.dto.response.NotificationResponse.builder()
                        .notificationId(n.getNotificationId())
                        .userId(n.getUserId() != null ? n.getUserId().getUserId() : null)
                        .type(n.getType())
                        .channel(n.getChannel())
                        .status(n.getStatus())
                        .retryCount(n.getRetryCount())
                        .maxRetries(n.getMaxRetries())
                        .createdAt(n.getCreatedAt())
                        .lastAttemptAt(n.getLastAttemptAt())
                        .deliveredAt(n.getDeliveredAt())
                        .deadLetterReason(n.getDeadLetterReason())
                        .build())
                .collect(Collectors.toList());
    }
}
