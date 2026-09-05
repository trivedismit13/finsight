package com.finsight.controller;

import com.finsight.dto.response.ApiResponse;
import com.finsight.dto.response.UserResponse;
import com.finsight.model.Role;
import com.finsight.repository.UserRepository;
import com.finsight.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/admin/users")
@RequiredArgsConstructor
public class UserController {
    private final UserService userService;
    private final UserRepository userRepository;

    private Long resolveUserId(UserDetails principal) {
        return userRepository.findByEmail(principal.getUsername())
                .orElseThrow(() -> new RuntimeException("Authenticated user not found"))
                .getUserId();
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<UserResponse>>> getAllUsers() {
        return ResponseEntity.ok(new ApiResponse<>("Users fetched", userService.getAllUsers()));
    }

    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<UserResponse>> updateRoleAndStatus(
            @PathVariable Long id,
            @RequestParam Role role,
            @RequestParam boolean isActive,
            @AuthenticationPrincipal UserDetails principal) {
        Long actorId = resolveUserId(principal);
        UserResponse updated = userService.updateRoleAndStatus(id, role, isActive, actorId);
        return ResponseEntity.ok(new ApiResponse<>("User updated", updated));
    }
    @PutMapping("/{id}/manager")
    public ResponseEntity<ApiResponse<UserResponse>> updateManager(
            @PathVariable Long id,
            @RequestParam(required = false) Long managerId,
            @AuthenticationPrincipal UserDetails principal) {
        Long actorId = resolveUserId(principal);
        UserResponse updated = userService.updateManager(id, managerId, actorId);
        return ResponseEntity.ok(new ApiResponse<>("Manager assigned", updated));
    }
}
