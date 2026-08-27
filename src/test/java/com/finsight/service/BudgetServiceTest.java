package com.finsight.service;

import com.finsight.model.CategoryBudget;
import com.finsight.model.User;
import com.finsight.repository.CategoryBudgetRepository;
import com.finsight.repository.FinancialRecordRepository;
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
    private CategoryBudgetRepository budgetRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private FinancialRecordRepository recordRepository;

    @Mock
    private NotificationDispatcherService notificationDispatcherService;

    @InjectMocks
    private BudgetService budgetService;

    private User testUser;
    private CategoryBudget testBudget;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);

        testUser = new User();
        testUser.setUserId(1L);

        testBudget = new CategoryBudget();
        testBudget.setCategory("Food");
        testBudget.setMonthYear("2026-08");
        testBudget.setBudgetAmount(new BigDecimal("100.00"));
        testBudget.setAlertSent(false);
    }

    @Test
    void testCheckBudgetExceeded_AlertNotSent_WhenUnderBudget() {
        when(recordRepository.sumExpensesByCategoryAndDateRange(eq("Food"), any(), any()))
                .thenReturn(new BigDecimal("50.00"));

        budgetService.checkBudgetExceeded(testBudget, 1L);

        verify(notificationDispatcherService, never()).enqueueNotification(any(), any(), any());
        assertFalse(testBudget.isAlertSent());
    }

    @Test
    void testCheckBudgetExceeded_SendsAlert_WhenOverBudget() {
        when(recordRepository.sumExpensesByCategoryAndDateRange(eq("Food"), any(), any()))
                .thenReturn(new BigDecimal("150.00"));
        when(budgetRepository.markAlertSentIfFalse(testBudget.getBudgetId())).thenReturn(1);

        budgetService.checkBudgetExceeded(testBudget, 1L);

        verify(notificationDispatcherService, times(1)).enqueueNotification(eq(1L), eq("BUDGET_ALERT"), anyString());
    }

    @Test
    void testCheckBudgetExceeded_PreventsAlertSpam() {
        testBudget.setAlertSent(true); // Alert already sent previously

        when(recordRepository.sumExpensesByCategoryAndDateRange(eq("Food"), any(), any()))
                .thenReturn(new BigDecimal("200.00")); // Still over budget

        budgetService.checkBudgetExceeded(testBudget, 1L);

        // Should not send another notification
        verify(notificationDispatcherService, never()).enqueueNotification(any(), any(), any());
        assertTrue(testBudget.isAlertSent());
    }

    @Test
    void testCheckBudgetExceeded_ResetsAlertState_WhenUnderBudgetAgain() {
        testBudget.setAlertSent(true); // Alert was sent previously

        when(recordRepository.sumExpensesByCategoryAndDateRange(eq("Food"), any(), any()))
                .thenReturn(new BigDecimal("50.00")); // Now under budget (e.g. record deleted)

        budgetService.checkBudgetExceeded(testBudget, 1L);

        verify(notificationDispatcherService, never()).enqueueNotification(any(), any(), any());
        assertFalse(testBudget.isAlertSent()); // State should be reset
        verify(budgetRepository, times(1)).save(testBudget);
    }

    @Test
    void testCreateOrUpdateBudget_ResetsAlertState_WhenLimitIncreased() {
        testBudget.setAlertSent(true);
        when(userRepository.findById(1L)).thenReturn(Optional.of(testUser));
        when(budgetRepository.findByCategoryAndMonthYear("Food", "2026-08")).thenReturn(Optional.of(testBudget));
        
        // Total spent is 150. Old limit was 100. New limit is 200.
        when(recordRepository.sumExpensesByCategoryAndDateRange(eq("Food"), any(), any())).thenReturn(new BigDecimal("150.00"));
        when(budgetRepository.save(any(CategoryBudget.class))).thenAnswer(invocation -> invocation.getArgument(0));

        CategoryBudget updated = budgetService.createOrUpdateBudget(1L, "Food", "2026-08", new BigDecimal("200.00"));

        assertFalse(updated.isAlertSent()); // Because 150 <= 200, state resets
        assertEquals(new BigDecimal("200.00"), updated.getBudgetAmount());
    }
}
