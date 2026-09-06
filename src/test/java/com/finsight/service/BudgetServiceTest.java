package com.finsight.service;

import com.finsight.model.Budget;
import com.finsight.model.User;
import com.finsight.repository.BudgetRepository;
import com.finsight.repository.ExpenseRepository;
import com.finsight.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.math.BigDecimal;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

public class BudgetServiceTest {

    @Mock
    private BudgetRepository budgetRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private ExpenseRepository expenseRepository;

    @Mock
    private NotificationDispatcherService notificationDispatcherService;

    @Mock
    private AuditLogService auditLogService;

    @InjectMocks
    private BudgetService budgetService;

    private User testUser;
    private Budget testBudget;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);

        testUser = new User();
        testUser.setUserId(1L);

        testBudget = new Budget();
        testBudget.setBudgetId(100L);
        testBudget.setCategory(com.finsight.model.ExpenseCategory.MEALS);
        testBudget.setMonthYear("2026-08");
        testBudget.setBudgetAmount(new BigDecimal("100.00"));
        testBudget.setAlertSent(false);

        User adminUser = new User();
        adminUser.setUserId(99L);
        adminUser.setRole(com.finsight.model.Role.FINANCE_ADMIN);
        when(userRepository.findByRoleAndIsActiveTrue(com.finsight.model.Role.FINANCE_ADMIN))
                .thenReturn(java.util.Collections.singletonList(adminUser));
    }

    @Test
    void testCheckBudgetExceeded_AlertNotSent_WhenUnderBudget() {
        when(expenseRepository.sumExpensesByCategoryAndDateRange(eq(com.finsight.model.ExpenseCategory.MEALS), any(), any()))
                .thenReturn(new BigDecimal("50.00"));

        budgetService.checkBudgetExceeded(testBudget);

        verify(notificationDispatcherService, never()).enqueueNotification(any(), any(), any());
        assertFalse(testBudget.isAlertSent());
    }

    @Test
    void testCheckBudgetExceeded_SendsAlert_WhenOverBudget() {
        when(expenseRepository.sumExpensesByCategoryAndDateRange(eq(com.finsight.model.ExpenseCategory.MEALS), any(), any()))
                .thenReturn(new BigDecimal("150.00"));
        when(budgetRepository.markAlertSentIfFalse(testBudget.getBudgetId())).thenReturn(1);

        budgetService.checkBudgetExceeded(testBudget);

        verify(notificationDispatcherService, times(1)).enqueueNotification(eq(99L), eq("BUDGET_ALERT"), anyString());
    }

    @Test
    void testCheckBudgetExceeded_PreventsAlertSpam() {
        testBudget.setAlertSent(true); // Alert already sent previously

        when(expenseRepository.sumExpensesByCategoryAndDateRange(eq(com.finsight.model.ExpenseCategory.MEALS), any(), any()))
                .thenReturn(new BigDecimal("200.00")); // Still over budget

        budgetService.checkBudgetExceeded(testBudget);

        // Should not send another notification
        verify(notificationDispatcherService, never()).enqueueNotification(any(), any(), any());
        assertTrue(testBudget.isAlertSent());
    }

    @Test
    void testCheckBudgetExceeded_ResetsAlertState_WhenUnderBudgetAgain() {
        testBudget.setAlertSent(true); // Alert was sent previously

        when(expenseRepository.sumExpensesByCategoryAndDateRange(eq(com.finsight.model.ExpenseCategory.MEALS), any(), any()))
                .thenReturn(new BigDecimal("50.00")); // Now under budget (e.g. record deleted)

        budgetService.checkBudgetExceeded(testBudget);

        verify(notificationDispatcherService, never()).enqueueNotification(any(), any(), any());
        assertFalse(testBudget.isAlertSent()); // State should be reset
        verify(budgetRepository, times(1)).save(testBudget);
    }

    @Test
    void testCreateOrUpdateBudget_ResetsAlertState_WhenLimitIncreased() {
        testBudget.setAlertSent(true);
        when(userRepository.findById(1L)).thenReturn(Optional.of(testUser));
        when(budgetRepository.findByCategoryAndMonthYear(com.finsight.model.ExpenseCategory.MEALS, "2026-08")).thenReturn(Optional.of(testBudget));
        
        // Total spent is 150. Old limit was 100. New limit is 200.
        when(expenseRepository.sumExpensesByCategoryAndDateRange(eq(com.finsight.model.ExpenseCategory.MEALS), any(), any())).thenReturn(new BigDecimal("150.00"));
        when(budgetRepository.save(any(Budget.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Budget updated = budgetService.createOrUpdateBudget(1L, com.finsight.model.ExpenseCategory.MEALS, "2026-08", new BigDecimal("200.00"));

        assertFalse(updated.isAlertSent()); // Because 150 <= 200, state resets
        assertEquals(new BigDecimal("200.00"), updated.getBudgetAmount());
    }

    @Test
    void testCheckBudgetExceeded_DateBoundariesAreCorrect() {
        testBudget.setMonthYear("2026-08");
        when(expenseRepository.sumExpensesByCategoryAndDateRange(eq(com.finsight.model.ExpenseCategory.MEALS), any(), any()))
                .thenReturn(new BigDecimal("50.00"));

        budgetService.checkBudgetExceeded(testBudget);

        org.mockito.ArgumentCaptor<java.time.LocalDate> startCaptor = org.mockito.ArgumentCaptor.forClass(java.time.LocalDate.class);
        org.mockito.ArgumentCaptor<java.time.LocalDate> endCaptor = org.mockito.ArgumentCaptor.forClass(java.time.LocalDate.class);

        verify(expenseRepository).sumExpensesByCategoryAndDateRange(
                eq(com.finsight.model.ExpenseCategory.MEALS),
                startCaptor.capture(),
                endCaptor.capture()
        );

        java.time.LocalDate capturedStart = startCaptor.getValue();
        java.time.LocalDate capturedEnd = endCaptor.getValue();

        assertEquals(java.time.LocalDate.of(2026, 8, 1), capturedStart, "Start date should be included (Aug 1)");
        assertEquals(java.time.LocalDate.of(2026, 8, 31), capturedEnd, "End date should be included (Aug 31)");
        assertNotEquals(java.time.LocalDate.of(2026, 9, 1), capturedEnd, "September 1 should be excluded");
        assertNotEquals(java.time.LocalDate.of(2026, 7, 31), capturedStart, "July 31 should be excluded");
    }
}
