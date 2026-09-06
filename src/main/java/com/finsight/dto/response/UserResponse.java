package com.finsight.dto.response;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class UserResponse {
    private Long userId;
    private String name;
    private String email;
    private String role;
    private Long managerId;
    private boolean isActive;
}
