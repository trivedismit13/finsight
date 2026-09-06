package com.finsight.service;

import com.finsight.model.Budget;
import com.finsight.model.User;
import com.finsight.repository.BudgetRepository;
import com.finsight.repository.ExpenseRepository;
import com.finsight.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class BudgetService {
    private final BudgetRepository budgetRepository;
    private final UserRepository userRepository;
    private final ExpenseRepository expenseRepository;
    private final NotificationDispatcherService notificationDispatcherService;
    private final AuditLogService auditLogService;

    @Transactional
    @PreAuthorize("hasRole('FINANCE_ADMIN')")
    public Budget createOrUpdateBudget(Long adminId, com.finsight.model.ExpenseCategory category, String monthYear, BigDecimal limit) {
        User admin = userRepository.findById(adminId).orElseThrow();
        boolean isNew = false;
        Budget budget = budgetRepository.findByCategoryAndMonthYear(category, monthYear).orElse(null);
        BigDecimal oldAmount = BigDecimal.ZERO;
        if (budget == null) {
            budget = new Budget();
            isNew = true;
            budget.setCreatedBy(admin);
        } else {
            oldAmount = budget.getBudgetAmount();
        }

        budget.setCategory(category);
        budget.setMonthYear(monthYear);
        budget.setBudgetAmount(limit);

        // Reset alert state if the limit is increased or total spending is now within budget
        java.time.YearMonth ym = java.time.YearMonth.parse(monthYear);
        java.time.LocalDate startDate = ym.atDay(1);
        java.time.LocalDate endDate = ym.atEndOfMonth();
        BigDecimal totalSpent = expenseRepository.sumExpensesByCategoryAndDateRange(category, startDate, endDate);
        if (totalSpent.compareTo(limit) <= 0) {
            budget.setAlertSent(false);
        }

        budget = budgetRepository.save(budget);

        if (isNew) {
            auditLogService.record(adminId, "CREATE_BUDGET", "BUDGET", budget.getBudgetId(),
                    String.format("Created budget for %s/%s: limit %.2f", category, monthYear, limit));
        } else {
            auditLogService.record(adminId, "UPDATE_BUDGET", "BUDGET", budget.getBudgetId(),
                    String.format("Updated budget for %s/%s from %.2f to %.2f", category, monthYear, oldAmount, limit));
        }

        // Trigger alert check after save
        checkBudgetExceeded(budget);

        return budget;
    }

    /**
     * Checks whether actual spending for the given category/month exceeds the budget.
     * Called both after budget creation and after every new EXPENSE record is created.
     */
    public void checkBudgetExceeded(Budget budget) {
        java.time.YearMonth ym = java.time.YearMonth.parse(budget.getMonthYear());
        java.time.LocalDate startDate = ym.atDay(1);
        java.time.LocalDate endDate = ym.atEndOfMonth();
        BigDecimal totalSpent = expenseRepository.sumExpensesByCategoryAndDateRange(budget.getCategory(), startDate, endDate);
        if (totalSpent.compareTo(budget.getBudgetAmount()) > 0) {
            // Use atomic update to prevent duplicate alerts from concurrent expenses
            int updated = budgetRepository.markAlertSentIfFalse(budget.getBudgetId());
            if (updated > 0) {
                java.util.List<User> admins = userRepository.findByRoleAndIsActiveTrue(com.finsight.model.Role.FINANCE_ADMIN);
                for (User adminUser : admins) {
                    notificationDispatcherService.enqueueNotification(
                            adminUser.getUserId(),
                            "BUDGET_ALERT",
                            String.format("Budget exceeded for category '%s' in %s: spent %.2f / limit %.2f",
                                    budget.getCategory(), budget.getMonthYear(), totalSpent, budget.getBudgetAmount())
                    );
                }
            }
        } else {
            // Under budget (maybe an expense was deleted/lowered), reset alert state
            // It's safe to just set it to false and save because we only care about reverting the flag.
            // If it's already false, saving false is a no-op.
            if (budget.isAlertSent()) {
                budget.setAlertSent(false);
                budgetRepository.save(budget);
            }
        }
    }

    /**
     * Looks up the active budget for a category/month and checks if the given spending exceeds it.
     * Triggered from ExpenseService after an EXPENSE record is created.
     */
    public void checkBudgetExceededAfterRecord(com.finsight.model.ExpenseCategory category, String monthYear) {
        Optional<Budget> budgetOpt = budgetRepository.findByCategoryAndMonthYear(category, monthYear);
        budgetOpt.ifPresent(budget ->
                checkBudgetExceeded(budget)
        );
    }
}
