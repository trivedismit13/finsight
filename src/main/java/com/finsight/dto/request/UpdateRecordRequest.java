package com.finsight.dto.request;

import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDate;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

@Data
public class UpdateRecordRequest {
    @jakarta.validation.constraints.NotNull
    @jakarta.validation.constraints.DecimalMin(value = "0.01")
    @jakarta.validation.constraints.DecimalMax(value = "9999999999999.99")
    @jakarta.validation.constraints.Digits(integer = 13, fraction = 2)
    private BigDecimal amount;
    @NotBlank
    @Pattern(regexp = "^(INCOME|EXPENSE)$", message = "Type must be INCOME or EXPENSE")
    private String type;
    @NotBlank
    private String category;
    @jakarta.validation.constraints.NotNull
    private LocalDate recordDate;
    private String description;
    // Client must send back the version they read — Hibernate uses this for optimistic lock checking
    private Long version;
}
