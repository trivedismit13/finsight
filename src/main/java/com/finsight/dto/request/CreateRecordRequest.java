package com.finsight.dto.request;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDate;

@Data
public class CreateRecordRequest {
    @NotNull
    @DecimalMin(value = "0.01")
    @jakarta.validation.constraints.DecimalMax(value = "9999999999999.99")
    @jakarta.validation.constraints.Digits(integer = 13, fraction = 2)
    private BigDecimal amount;

    @NotBlank
    @jakarta.validation.constraints.Pattern(regexp = "^(INCOME|EXPENSE)$", message = "Type must be INCOME or EXPENSE")
    private String type; // INCOME or EXPENSE

    @NotBlank
    private String category;

    @NotNull
    private LocalDate recordDate;

    private String description;

    // Optional: if provided, guarantees exactly-once semantics on duplicate submissions
    private String idempotencyKey;
}
