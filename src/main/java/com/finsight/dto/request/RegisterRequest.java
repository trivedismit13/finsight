package com.finsight.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class RegisterRequest {
    @NotBlank
    private String name;
    @Email @NotBlank
    private String email;
    @NotBlank
    @jakarta.validation.constraints.Size(min = 8, message = "Password must be at least 8 characters")
    private String password;
}
