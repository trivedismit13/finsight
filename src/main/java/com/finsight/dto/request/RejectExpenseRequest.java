package com.finsight.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class RejectExpenseRequest {
    @NotBlank(message = "Rejection reason is required")
    private String reason;
}
