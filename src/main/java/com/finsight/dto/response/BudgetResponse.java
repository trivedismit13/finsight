package com.finsight.dto.response;

import lombok.Builder;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Builder
public class BudgetResponse {
    private Long budgetId;
    private String category;
    private String monthYear;
    private BigDecimal budgetAmount;
    private boolean isAlertSent;
    private Long createdByUserId;
    private LocalDateTime createdAt;
}
