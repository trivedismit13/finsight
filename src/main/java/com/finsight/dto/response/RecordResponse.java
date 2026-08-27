package com.finsight.dto.response;

import lombok.Builder;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@Builder
public class RecordResponse {
    private Long recordId;
    private Long createdByUserId;
    private String createdByName;
    private BigDecimal amount;
    private String type;
    private String category;
    private LocalDate recordDate;
    private String description;
    private boolean isDeleted;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private Long version;
    private String idempotencyKey;
}
