package com.finsight.controller;

import com.finsight.dto.response.ApiResponse;
import com.finsight.model.Budget;
import com.finsight.service.BudgetService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;

@RestController
@RequestMapping("/api/budgets")
@RequiredArgsConstructor
public class BudgetController {
    private final BudgetService budgetService;
    private Long resolveUserId(UserDetails principal) {
        if (principal instanceof com.finsight.security.CustomUserDetails) {
            return ((com.finsight.security.CustomUserDetails) principal).getUserId();
        }
        throw new RuntimeException("Authenticated user not found or invalid type");
    }

    @PostMapping
    public ResponseEntity<ApiResponse<com.finsight.dto.response.BudgetResponse>> setBudget(
            @RequestParam com.finsight.model.ExpenseCategory category,
            @RequestParam String monthYear,
            @RequestParam BigDecimal limit,
            @AuthenticationPrincipal UserDetails principal) {
        Long adminId = resolveUserId(principal);
        Budget budget = budgetService.createOrUpdateBudget(adminId, category, monthYear, limit);
        com.finsight.dto.response.BudgetResponse response = com.finsight.dto.response.BudgetResponse.builder()
                .budgetId(budget.getBudgetId())
                .category(budget.getCategory().name())
                .monthYear(budget.getMonthYear())
                .budgetAmount(budget.getBudgetAmount())
                .isAlertSent(budget.isAlertSent())
                .createdByUserId(budget.getCreatedBy() != null ? budget.getCreatedBy().getUserId() : null)
                .createdAt(budget.getCreatedAt())
                .build();
        return ResponseEntity.ok(new ApiResponse<>("Budget set", response));
    }
}
