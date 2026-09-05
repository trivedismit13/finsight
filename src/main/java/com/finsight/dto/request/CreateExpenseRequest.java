package com.finsight.dto.request;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDate;

@Data
public class CreateExpenseRequest {
    @NotNull
    @DecimalMin(value = "0.01")
    @jakarta.validation.constraints.DecimalMax(value = "9999999999999.99")
    @jakarta.validation.constraints.Digits(integer = 13, fraction = 2)
    private BigDecimal amount;


    @NotNull
    private com.finsight.model.ExpenseCategory category;

    @NotNull
    private LocalDate expenseDate;

    private String description;
}


