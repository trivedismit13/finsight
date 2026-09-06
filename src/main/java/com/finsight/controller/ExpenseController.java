package com.finsight.controller;

import com.finsight.dto.request.CreateExpenseRequest;
import com.finsight.dto.request.RejectExpenseRequest;
import com.finsight.dto.request.UpdateExpenseRequest;
import com.finsight.dto.response.ApiResponse;
import com.finsight.dto.response.ExpenseResponse;
import com.finsight.service.ExpenseService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/expenses")
@RequiredArgsConstructor
public class ExpenseController {
    private final ExpenseService service;
    private Long resolveUserId(UserDetails principal) {
        if (principal instanceof com.finsight.security.CustomUserDetails) {
            return ((com.finsight.security.CustomUserDetails) principal).getUserId();
        }
        throw new RuntimeException("Authenticated user not found or invalid type");
    }

    @GetMapping
    public ResponseEntity<ApiResponse<org.springframework.data.domain.Page<ExpenseResponse>>> getAllExpenses(
            @RequestParam(value = "page", required = false) Integer pageNumber,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) java.time.LocalDate startDate,
            @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) java.time.LocalDate endDate,
            org.springframework.data.domain.Pageable pageable,
            @AuthenticationPrincipal UserDetails principal) {

        if (pageNumber != null && pageNumber < 0) {
            throw new com.finsight.exception.InvalidRequestException("Page number cannot be less than zero.");
        }

        if (status != null) {
            try {
                com.finsight.model.ExpenseStatus.valueOf(status);
            } catch (IllegalArgumentException e) {
                throw new com.finsight.exception.InvalidRequestException("Status is invalid");
            }
        }

        Long actorId = resolveUserId(principal);
        return ResponseEntity.ok(new ApiResponse<>("Expenses fetched", service.getAllExpenses(status, category, startDate, endDate, pageable, actorId)));
    }

    @PostMapping
    @PreAuthorize("hasRole('EMPLOYEE')")
    public ResponseEntity<ApiResponse<ExpenseResponse>> create(
            @Valid @RequestBody CreateExpenseRequest req,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @AuthenticationPrincipal UserDetails principal) {
        Long actorId = resolveUserId(principal);
        ExpenseResponse rec = service.createExpense(req, idempotencyKey, actorId);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new ApiResponse<>("Expense created", rec));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('EMPLOYEE')")
    public ResponseEntity<ApiResponse<ExpenseResponse>> update(
            @PathVariable Long id,
            @Valid @RequestBody UpdateExpenseRequest req,
            @AuthenticationPrincipal UserDetails principal) {
        Long actorId = resolveUserId(principal);
        ExpenseResponse rec = service.updateExpense(id, req, actorId);
        return ResponseEntity.ok(new ApiResponse<>("Expense updated", rec));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('EMPLOYEE')")
    public ResponseEntity<Void> delete(
            @PathVariable Long id,
            @AuthenticationPrincipal UserDetails principal) {
        Long actorId = resolveUserId(principal);
        service.deleteExpense(id, actorId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/submit")
    @PreAuthorize("hasRole('EMPLOYEE')")
    public ResponseEntity<ApiResponse<ExpenseResponse>> submit(
            @PathVariable Long id,
            @AuthenticationPrincipal UserDetails principal) {
        Long actorId = resolveUserId(principal);
        ExpenseResponse rec = service.submitExpense(id, actorId);
        return ResponseEntity.ok(new ApiResponse<>("Expense submitted", rec));
    }

    @GetMapping("/team")
    @PreAuthorize("hasAnyRole('MANAGER', 'FINANCE_ADMIN')")
    public ResponseEntity<ApiResponse<org.springframework.data.domain.Slice<ExpenseResponse>>> getTeamExpenses(
            org.springframework.data.domain.Pageable pageable,
            @AuthenticationPrincipal UserDetails principal) {
        Long managerId = resolveUserId(principal);
        return ResponseEntity.ok(new ApiResponse<>("Team expenses fetched", service.getTeamExpenses(managerId, pageable)));
    }

    @PostMapping("/{id}/approve")
    @PreAuthorize("hasRole('MANAGER')")
    public ResponseEntity<ApiResponse<ExpenseResponse>> approve(
            @PathVariable Long id,
            @AuthenticationPrincipal UserDetails principal) {
        Long managerId = resolveUserId(principal);
        ExpenseResponse rec = service.approveExpense(id, managerId);
        return ResponseEntity.ok(new ApiResponse<>("Expense approved", rec));
    }

    @PostMapping("/{id}/reject")
    @PreAuthorize("hasRole('MANAGER')")
    public ResponseEntity<ApiResponse<ExpenseResponse>> reject(
            @PathVariable Long id,
            @Valid @RequestBody RejectExpenseRequest req,
            @AuthenticationPrincipal UserDetails principal) {
        Long managerId = resolveUserId(principal);
        ExpenseResponse rec = service.rejectExpense(id, managerId, req.getReason());
        return ResponseEntity.ok(new ApiResponse<>("Expense rejected", rec));
    }

    @GetMapping("/admin/all")
    @PreAuthorize("hasRole('FINANCE_ADMIN')")
    public ResponseEntity<ApiResponse<org.springframework.data.domain.Page<ExpenseResponse>>> getAllExpensesAdmin(
            @RequestParam(value = "page", required = false) Integer pageNumber,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) java.time.LocalDate startDate,
            @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) java.time.LocalDate endDate,
            org.springframework.data.domain.Pageable pageable,
            @AuthenticationPrincipal UserDetails principal) {
        
        if (pageNumber != null && pageNumber < 0) {
            throw new com.finsight.exception.InvalidRequestException("Page number cannot be less than zero.");
        }

        Long actorId = resolveUserId(principal);
        return ResponseEntity.ok(new ApiResponse<>("All expenses fetched", service.getAllExpenses(status, category, startDate, endDate, pageable, actorId)));
    }

    @PostMapping("/admin/{id}/process")
    @PreAuthorize("hasRole('FINANCE_ADMIN')")
    public ResponseEntity<ApiResponse<ExpenseResponse>> process(
            @PathVariable Long id,
            @AuthenticationPrincipal UserDetails principal) {
        Long adminId = resolveUserId(principal);
        ExpenseResponse rec = service.processExpense(id, adminId);
        return ResponseEntity.ok(new ApiResponse<>("Expense processed", rec));
    }
}
