package com.finsight.dto.request;

import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDate;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

@Data
public class UpdateExpenseRequest {
    @jakarta.validation.constraints.NotNull
    @jakarta.validation.constraints.DecimalMin(value = "0.01")
    @jakarta.validation.constraints.DecimalMax(value = "9999999999999.99")
    @jakarta.validation.constraints.Digits(integer = 13, fraction = 2)
    private BigDecimal amount;

    @jakarta.validation.constraints.NotNull
    private com.finsight.model.ExpenseCategory category;
    @jakarta.validation.constraints.NotNull
    private LocalDate expenseDate;
    private String description;
    // Client must send back the version they read — Hibernate uses this for optimistic lock checking
    private Long version;
}
