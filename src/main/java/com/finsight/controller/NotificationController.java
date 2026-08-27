package com.finsight.controller;

import com.finsight.dto.response.ApiResponse;
import com.finsight.service.NotificationService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/notifications")
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationService notificationService;

    @GetMapping("/dlq")
    public ResponseEntity<ApiResponse<List<com.finsight.dto.response.NotificationResponse>>> getDeadLetterQueue() {
        return ResponseEntity.ok(new ApiResponse<>("Dead Letter Queue fetched", notificationService.getDeadLetterQueue()));
    }
}
