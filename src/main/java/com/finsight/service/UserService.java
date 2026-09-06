package com.finsight.service;

import com.finsight.dto.response.UserResponse;
import com.finsight.exception.ResourceNotFoundException;
import com.finsight.model.Role;
import com.finsight.model.User;
import com.finsight.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class UserService {
    private final UserRepository userRepository;
    private final AuditLogService auditLogService;

    @PreAuthorize("hasRole('FINANCE_ADMIN')")
    public List<UserResponse> getAllUsers() {
        return userRepository.findAll().stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    @Transactional
    @PreAuthorize("hasRole('FINANCE_ADMIN')")
    public UserResponse updateRoleAndStatus(Long userId, Role role, boolean isActive, Long actorUserId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + userId));

        String oldRole = user.getRole().name();
        boolean oldActive = user.isActive();

        if (userId.equals(actorUserId) && (role != user.getRole() || isActive != user.isActive())) {
            throw new com.finsight.exception.InvalidRequestException("Users cannot change their own role or active status");
        }

        
        if (role == Role.EMPLOYEE && isActive && user.getManager() == null) {
            throw new com.finsight.exception.InvalidRequestException("Active employees must have a manager assigned");
        }
        if (user.getRole() == Role.MANAGER && (!isActive || role != Role.MANAGER)) {
            if (userRepository.countByManagerAndIsActiveTrue(user) > 0) {
                throw new com.finsight.exception.InvalidRequestException("Cannot deactivate or demote a manager with active direct reports. Reassign them first.");
            }
        }
        user.setRole(role);
        user.setActive(isActive);
        User saved = userRepository.save(user);

        // Audit role/status changes — BUG-17 fix
        auditLogService.record(actorUserId, "ROLE_CHANGE", "USER", userId,
                String.format("role: %s -> %s, isActive: %s -> %s", oldRole, role.name(), oldActive, isActive));

        return toResponse(saved);
    }

    @Transactional
    @PreAuthorize("hasRole('FINANCE_ADMIN')")
    public UserResponse updateManager(Long userId, Long managerId, Long actorUserId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + userId));

        Long oldManagerId = user.getManager() != null ? user.getManager().getUserId() : null;

        if (user.getRole() != Role.EMPLOYEE) {
            throw new com.finsight.exception.InvalidRequestException("Only EMPLOYEE can be assigned a manager");
        }

        
        if (managerId == null && user.isActive()) {
            throw new com.finsight.exception.InvalidRequestException("Cannot remove manager from an active employee");
        }
        if (managerId != null) {
            User manager = userRepository.findById(managerId)
                    .orElseThrow(() -> new ResourceNotFoundException("Manager not found: " + managerId));
            if (manager.getRole() != Role.MANAGER) {
                throw new com.finsight.exception.InvalidRequestException("Assigned manager must have MANAGER role");
            }
            if (manager.getUserId().equals(user.getUserId())) {
                throw new com.finsight.exception.InvalidRequestException("User cannot be their own manager");
            }
            user.setManager(manager);
        } else {
            user.setManager(null);
        }

        User saved = userRepository.save(user);

        auditLogService.record(actorUserId, "MANAGER_ASSIGNMENT", "USER", userId,
                String.format("managerId: %s -> %s", oldManagerId, managerId));

        return toResponse(saved);
    }

    public UserResponse toResponse(User user) {
        return UserResponse.builder()
                .userId(user.getUserId())
                .name(user.getName())
                .email(user.getEmail())
                .role(user.getRole().name())
                .managerId(user.getManager() != null ? user.getManager().getUserId() : null)
                .build();
    }
}
