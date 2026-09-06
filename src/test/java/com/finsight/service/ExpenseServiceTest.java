package com.finsight.service;

import com.finsight.dto.request.CreateExpenseRequest;
import com.finsight.dto.request.UpdateExpenseRequest;
import com.finsight.dto.response.ExpenseResponse;
import com.finsight.exception.ResourceNotFoundException;
import com.finsight.model.Expense;
import com.finsight.model.User;
import com.finsight.repository.ExpenseRepository;
import com.finsight.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class ExpenseServiceTest {

    @Mock private ExpenseRepository expenseRepository;
    @Mock private UserRepository userRepository;
    @Mock private AuditLogService auditLogService;
    @Mock private BudgetService budgetService;

    @InjectMocks
    private ExpenseService expenseService;

    private User testUser;

    @BeforeEach
    void setUp() {
        testUser = new User();
        testUser.setUserId(1L);
        testUser.setName("Test Admin");
        org.springframework.test.util.ReflectionTestUtils.setField(expenseService, "self", expenseService);
    }

    /**
     * Given: a record with version=0. Two clients both read version=0.
     * When: first update passes, second update arrives with version=0 but DB is now at version=1.
     * Then: The service detects the version mismatch and throws OptimisticLockingFailureException.
     *
     * This tests the explicit version pre-check we implemented (do NOT call setVersion on entity).
     */
    @Test
    void testConcurrentUpdate_optimisticLock_secondWriterGetsConflict() {
        Expense expense = new Expense();
        expense.setCategory(com.finsight.model.ExpenseCategory.MEALS);
        expense.setExpenseId(10L);
        expense.setVersion(1L); // DB is at version 1 (first writer already committed)
        expense.setCreatedBy(testUser);

        when(expenseRepository.findByIdAndIsDeletedFalse(10L)).thenReturn(Optional.of(expense));

        UpdateExpenseRequest req = new UpdateExpenseRequest();
        req.setVersion(0L); // Client still has the old version=0

        // Should throw because 1 (DB) != 0 (client)
        assertThrows(OptimisticLockingFailureException.class,
                () -> expenseService.updateExpense(10L, req, 1L));

        // The record should NOT have been saved
        verify(expenseRepository, never()).save(any());
    }

    /**
     * Given: a record already created with idempotencyKey="abc-123".
     * When: create is called again with the same key (race condition: check passes, then DB throws on insert).
     * Then: no new row is inserted; the existing record is returned.
     */
    @Test
    void testIdempotentCreate_duplicateKeyReturnsExistingRecord() {
        String key = "abc-123";
        Expense existing = new Expense();
        existing.setCategory(com.finsight.model.ExpenseCategory.MEALS);
        existing.setExpenseId(99L);
                existing.setCreatedBy(testUser);
        existing.setAmount(BigDecimal.valueOf(100));
        existing.setCategory(com.finsight.model.ExpenseCategory.MEALS);
        existing.setExpenseDate(LocalDate.of(2026, 8, 18));

        CreateExpenseRequest req = new CreateExpenseRequest();
                req.setAmount(BigDecimal.valueOf(100));
        req.setCategory(com.finsight.model.ExpenseCategory.MEALS);
        req.setExpenseDate(LocalDate.of(2026, 8, 18));

        when(userRepository.findById(1L)).thenReturn(Optional.of(testUser));
        // First call: key not found (race window); save throws; second lookup finds it
        when(expenseRepository.findByCreatedBy_UserIdAndIdempotencyKey(1L, key))
                .thenReturn(Optional.empty())       // initial check
                .thenReturn(Optional.of(existing)); // after DataIntegrityViolationException
        when(expenseRepository.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("duplicate key"));

        ExpenseResponse result = expenseService.createExpense(req, key, 1L);

        assertEquals(99L, result.getExpenseId());
        // Only one save attempt was made
        verify(expenseRepository, times(1)).saveAndFlush(any(Expense.class));
    }

    /**
     * Given: a record with the correct version.
     * When: update is called with the matching version.
     * Then: the update succeeds and auditLog is called.
     */
    @Test
    void testUpdateRecord_matchingVersion_succeeds() {
        Expense expense = new Expense();
        expense.setCategory(com.finsight.model.ExpenseCategory.MEALS);
        expense.setExpenseId(5L);
        expense.setVersion(2L);
        expense.setCreatedBy(testUser);
        expense.setAmount(BigDecimal.valueOf(100));
        expense.setCategory(com.finsight.model.ExpenseCategory.MEALS);

        when(expenseRepository.findByIdAndIsDeletedFalse(5L)).thenReturn(Optional.of(expense));
        when(expenseRepository.save(any())).thenReturn(expense);

        UpdateExpenseRequest req = new UpdateExpenseRequest();
        req.setVersion(2L); // matches DB version
        req.setAmount(BigDecimal.valueOf(200));
        req.setCategory(com.finsight.model.ExpenseCategory.TRANSPORT);
        req.setExpenseDate(LocalDate.of(2026, 8, 18));

        ExpenseResponse result = expenseService.updateExpense(5L, req, 1L);

        assertNotNull(result);
        verify(auditLogService).record(eq(1L), eq("UPDATE_EXPENSE"), eq("EXPENSE"), eq(5L), anyString());
    }

    /**
     * Given: record ID that does not exist.
     * When: updateRecord is called.
     * Then: ResourceNotFoundException is thrown.
     */
    @Test
    void testUpdateRecord_nonExistentId_throwsResourceNotFoundException() {
        when(expenseRepository.findByIdAndIsDeletedFalse(999L)).thenReturn(Optional.empty());

        UpdateExpenseRequest req = new UpdateExpenseRequest();
        req.setVersion(0L);

        assertThrows(ResourceNotFoundException.class,
                () -> expenseService.updateExpense(999L, req, 1L));
    }

    @Test
    void testIdempotentCreate_sameKeySamePayload_returnsExistingRecord() {
        String key = "ABC";
        Expense existing = new Expense();
        existing.setCategory(com.finsight.model.ExpenseCategory.MEALS);
        existing.setExpenseId(100L);
                existing.setCreatedBy(testUser);
        existing.setAmount(BigDecimal.valueOf(50));
        existing.setCategory(com.finsight.model.ExpenseCategory.OTHER);
        existing.setExpenseDate(LocalDate.of(2026, 8, 18));
        
        CreateExpenseRequest req = new CreateExpenseRequest();
                req.setAmount(BigDecimal.valueOf(50));
        req.setCategory(com.finsight.model.ExpenseCategory.OTHER);
        req.setExpenseDate(LocalDate.of(2026, 8, 18));

        when(expenseRepository.findByCreatedBy_UserIdAndIdempotencyKey(1L, key))
                .thenReturn(Optional.of(existing));

        ExpenseResponse result = expenseService.createExpense(req, key, 1L);

        assertEquals(100L, result.getExpenseId());
        verify(expenseRepository, never()).saveAndFlush(any());
    }

    @Test
    void testIdempotentCreate_sameKeyDifferentPayload_throwsException() {
        String key = "ABC";
        Expense existing = new Expense();
        existing.setCategory(com.finsight.model.ExpenseCategory.MEALS);
        existing.setExpenseId(100L);
                existing.setCreatedBy(testUser);
        existing.setAmount(BigDecimal.valueOf(50));
        existing.setCategory(com.finsight.model.ExpenseCategory.OTHER);
        existing.setExpenseDate(LocalDate.of(2026, 8, 18));
        
        CreateExpenseRequest req = new CreateExpenseRequest();
                req.setAmount(BigDecimal.valueOf(100)); // Different amount
        req.setCategory(com.finsight.model.ExpenseCategory.OTHER);
        req.setExpenseDate(LocalDate.of(2026, 8, 18));

        when(expenseRepository.findByCreatedBy_UserIdAndIdempotencyKey(1L, key))
                .thenReturn(Optional.of(existing));

        assertThrows(IllegalArgumentException.class, () -> expenseService.createExpense(req, key, 1L));
        verify(expenseRepository, never()).saveAndFlush(any());
    }

    @Test
    void testIdempotentCreate_crossUserCollision_succeedsIndependently() {
        String key = "ABC";
        CreateExpenseRequest req = new CreateExpenseRequest();
                req.setAmount(BigDecimal.valueOf(50));
        req.setCategory(com.finsight.model.ExpenseCategory.OTHER);
        req.setExpenseDate(LocalDate.of(2026, 8, 18));

        User userB = new User();
        userB.setUserId(2L);
        userB.setName("User B");

        when(userRepository.findById(1L)).thenReturn(Optional.of(testUser));
        when(userRepository.findById(2L)).thenReturn(Optional.of(userB));
        
        when(expenseRepository.findByCreatedBy_UserIdAndIdempotencyKey(1L, key))
                .thenReturn(Optional.empty());
        when(expenseRepository.findByCreatedBy_UserIdAndIdempotencyKey(2L, key))
                .thenReturn(Optional.empty());

        Expense saved1 = new Expense();
        saved1.setExpenseId(101L);
        saved1.setCreatedBy(testUser);
        saved1.setAmount(req.getAmount());
        saved1.setCategory(com.finsight.model.ExpenseCategory.OTHER);
        saved1.setExpenseDate(LocalDate.now());
        
        Expense saved2 = new Expense();
        saved2.setExpenseId(102L);
        saved2.setCreatedBy(userB);
        saved2.setAmount(req.getAmount());
        saved2.setCategory(com.finsight.model.ExpenseCategory.OTHER);
        saved2.setExpenseDate(LocalDate.now());

        when(expenseRepository.saveAndFlush(any(Expense.class))).thenAnswer(invocation -> {
            Expense f = invocation.getArgument(0);
            if (f != null && f.getCreatedBy() != null && f.getCreatedBy().getUserId().equals(1L)) {
                return saved1;
            }
            return saved2;
        });

        ExpenseResponse result1 = expenseService.createExpense(req, key, 1L);
        ExpenseResponse result2 = expenseService.createExpense(req, key, 2L);

        assertEquals(101L, result1.getExpenseId());
        assertEquals(102L, result2.getExpenseId());
        verify(expenseRepository, times(2)).saveAndFlush(any(Expense.class));
    }

    @Test
    void testDeleteRecord_auditsAction() {
        Expense expense = new Expense();
        expense.setCategory(com.finsight.model.ExpenseCategory.MEALS);
        expense.setExpenseId(5L);
        expense.setCreatedBy(testUser);

        when(expenseRepository.findByIdAndIsDeletedFalse(5L)).thenReturn(Optional.of(expense));

        expenseService.deleteExpense(5L, 1L);

        assertTrue(expense.isDeleted());
        verify(expenseRepository).save(expense);
        verify(auditLogService).record(eq(1L), eq("DELETE_EXPENSE"), eq("EXPENSE"), eq(5L), eq("Deleted expense: 5"));
    }

    @Test
    void testSubmitExpense_noManager_throwsException() {
        User creator = new User();
        creator.setUserId(1L);
        creator.setRole(com.finsight.model.Role.EMPLOYEE);
        creator.setActive(true);
        creator.setManager(null); // Manager-less

        Expense expense = new Expense();
        expense.setExpenseId(5L);
        expense.setStatus(com.finsight.model.ExpenseStatus.DRAFT);
        expense.setCategory(com.finsight.model.ExpenseCategory.MEALS);
        expense.setAmount(java.math.BigDecimal.valueOf(100));
        expense.setExpenseDate(java.time.LocalDate.now());
        expense.setCreatedBy(creator);

        when(expenseRepository.findByIdAndIsDeletedFalse(5L)).thenReturn(Optional.of(expense));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> expenseService.submitExpense(5L, 1L));
        assertEquals("Cannot submit expense: no manager assigned.", ex.getMessage());
    }

    @Test
    void testSubmitExpense_inactiveManager_throwsException() {
        User manager = new User();
        manager.setRole(com.finsight.model.Role.MANAGER);
        manager.setActive(false); // Inactive manager

        User creator = new User();
        creator.setUserId(1L);
        creator.setRole(com.finsight.model.Role.EMPLOYEE);
        creator.setActive(true);
        creator.setManager(manager);

        Expense expense = new Expense();
        expense.setExpenseId(5L);
        expense.setStatus(com.finsight.model.ExpenseStatus.DRAFT);
        expense.setCategory(com.finsight.model.ExpenseCategory.MEALS);
        expense.setAmount(java.math.BigDecimal.valueOf(100));
        expense.setExpenseDate(java.time.LocalDate.now());
        expense.setCreatedBy(creator);

        when(expenseRepository.findByIdAndIsDeletedFalse(5L)).thenReturn(Optional.of(expense));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> expenseService.submitExpense(5L, 1L));
        assertEquals("Cannot submit expense: assigned manager is inactive.", ex.getMessage());
    }

    @Test
    void testSubmitExpense_activeManager_succeeds() {
        User manager = new User();
        manager.setRole(com.finsight.model.Role.MANAGER);
        manager.setActive(true); // Active manager

        User creator = new User();
        creator.setUserId(1L);
        creator.setRole(com.finsight.model.Role.EMPLOYEE);
        creator.setActive(true);
        creator.setManager(manager);

        Expense expense = new Expense();
        expense.setExpenseId(5L);
        expense.setStatus(com.finsight.model.ExpenseStatus.DRAFT);
        expense.setCategory(com.finsight.model.ExpenseCategory.MEALS);
        expense.setAmount(java.math.BigDecimal.valueOf(100));
        expense.setCurrency("USD");
        expense.setExpenseDate(java.time.LocalDate.now());
        expense.setCreatedBy(creator);

        when(expenseRepository.findByIdAndIsDeletedFalse(5L)).thenReturn(Optional.of(expense));
        when(expenseRepository.save(any(Expense.class))).thenReturn(expense);

        ExpenseResponse response = expenseService.submitExpense(5L, 1L);
        
        assertEquals(com.finsight.model.ExpenseStatus.PENDING_APPROVAL.name(), response.getStatus());
        verify(auditLogService).record(eq(1L), eq("SUBMIT_EXPENSE"), eq("EXPENSE"), eq(5L), anyString());
    }
}
