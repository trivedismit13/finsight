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
    private final ExpenseRepository recordRepository;
    private final NotificationDispatcherService notificationDispatcherService;

    @Transactional
    @PreAuthorize("hasRole('ADMIN')")
    public Budget createOrUpdateBudget(Long adminId, String category, String monthYear, BigDecimal limit) {
        User admin = userRepository.findById(adminId).orElseThrow();
        Budget budget = budgetRepository.findByCategoryAndMonthYear(category, monthYear)
                .orElse(new Budget());

        budget.setCategory(category);
        budget.setMonthYear(monthYear);
        budget.setBudgetAmount(limit);
        budget.setCreatedBy(admin);

        // Reset alert state if the limit is increased or total spending is now within budget
        java.time.YearMonth ym = java.time.YearMonth.parse(monthYear);
        java.time.LocalDate startDate = ym.atDay(1);
        java.time.LocalDate endDateExclusive = ym.plusMonths(1).atDay(1);
        BigDecimal totalSpent = recordRepository.sumExpensesByCategoryAndDateRange(com.finsight.model.ExpenseCategory.valueOf(category), startDate, endDateExclusive);
        if (totalSpent.compareTo(limit) <= 0) {
            budget.setAlertSent(false);
        }

        budget = budgetRepository.save(budget);

        // Trigger alert check after save
        checkBudgetExceeded(budget, admin.getUserId());

        return budget;
    }

    /**
     * Checks whether actual spending for the given category/month exceeds the budget.
     * Called both after budget creation and after every new EXPENSE record is created.
     */
    public void checkBudgetExceeded(Budget budget, Long notifyUserId) {
        java.time.YearMonth ym = java.time.YearMonth.parse(budget.getMonthYear());
        java.time.LocalDate startDate = ym.atDay(1);
        java.time.LocalDate endDateExclusive = ym.plusMonths(1).atDay(1);
        BigDecimal totalSpent = recordRepository.sumExpensesByCategoryAndDateRange(com.finsight.model.ExpenseCategory.valueOf(budget.getCategory()), startDate, endDateExclusive);
        if (totalSpent.compareTo(budget.getBudgetAmount()) > 0) {
            // Use atomic update to prevent duplicate alerts from concurrent expenses
            int updated = budgetRepository.markAlertSentIfFalse(budget.getBudgetId());
            if (updated > 0) {
                notificationDispatcherService.enqueueNotification(
                        notifyUserId,
                        "BUDGET_ALERT",
                        String.format("Budget exceeded for category '%s' in %s: spent %.2f / limit %.2f",
                                budget.getCategory(), budget.getMonthYear(), totalSpent, budget.getBudgetAmount())
                );
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
    public void checkBudgetExceededAfterRecord(String category, String monthYear, Long notifyUserId) {
        Optional<Budget> budgetOpt = budgetRepository.findByCategoryAndMonthYear(category, monthYear);
        budgetOpt.ifPresent(budget ->
                checkBudgetExceeded(budget, notifyUserId)
        );
    }
}
