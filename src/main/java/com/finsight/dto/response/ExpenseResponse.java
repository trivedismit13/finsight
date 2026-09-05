package com.finsight.dto.response;

import lombok.Builder;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@Builder
public class ExpenseResponse {
    private Long expenseId;
    private Long createdByUserId;
    private String createdByName;
    private BigDecimal amount;
    private String currency;
    private String status;
    private String category;
    private LocalDate expenseDate;
    private String description;
    private boolean isDeleted;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private Long version;
    private String idempotencyKey;
    private LocalDateTime submittedAt;
    private LocalDateTime approvedAt;
    private Long approvedByUserId;
    private Long rejectedByUserId;
    private LocalDateTime rejectedAt;
    private String rejectionReason;
}
