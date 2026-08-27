package com.finsight.service;

import com.finsight.dto.request.CreateRecordRequest;
import com.finsight.dto.request.UpdateRecordRequest;
import com.finsight.dto.response.RecordResponse;
import com.finsight.exception.ResourceNotFoundException;
import com.finsight.model.FinancialRecord;
import com.finsight.model.User;
import com.finsight.repository.FinancialRecordRepository;
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
public class FinancialRecordServiceTest {

    @Mock private FinancialRecordRepository recordRepository;
    @Mock private UserRepository userRepository;
    @Mock private AuditLogService auditLogService;
    @Mock private BudgetService budgetService;

    @InjectMocks
    private FinancialRecordService recordService;

    private User testUser;

    @BeforeEach
    void setUp() {
        testUser = new User();
        testUser.setUserId(1L);
        testUser.setName("Test Admin");
        org.springframework.test.util.ReflectionTestUtils.setField(recordService, "self", recordService);
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
        FinancialRecord record = new FinancialRecord();
        record.setRecordId(10L);
        record.setVersion(1L); // DB is at version 1 (first writer already committed)
        record.setCreatedBy(testUser);

        when(recordRepository.findByIdAndIsDeletedFalse(10L)).thenReturn(Optional.of(record));

        UpdateRecordRequest req = new UpdateRecordRequest();
        req.setVersion(0L); // Client still has the old version=0

        // Should throw because 1 (DB) != 0 (client)
        assertThrows(OptimisticLockingFailureException.class,
                () -> recordService.updateRecord(10L, req, 1L));

        // The record should NOT have been saved
        verify(recordRepository, never()).save(any());
    }

    /**
     * Given: a record already created with idempotencyKey="abc-123".
     * When: create is called again with the same key (race condition: check passes, then DB throws on insert).
     * Then: no new row is inserted; the existing record is returned.
     */
    @Test
    void testIdempotentCreate_duplicateKeyReturnsExistingRecord() {
        String key = "abc-123";
        FinancialRecord existing = new FinancialRecord();
        existing.setRecordId(99L);
        existing.setIdempotencyKey(key);
        existing.setCreatedBy(testUser);
        existing.setAmount(BigDecimal.valueOf(100));
        existing.setType("EXPENSE");
        existing.setCategory("Food");
        existing.setRecordDate(LocalDate.of(2026, 8, 18));

        CreateRecordRequest req = new CreateRecordRequest();
        req.setIdempotencyKey(key);
        req.setAmount(BigDecimal.valueOf(100));
        req.setType("EXPENSE");
        req.setCategory("Food");
        req.setRecordDate(LocalDate.of(2026, 8, 18));

        when(userRepository.findById(1L)).thenReturn(Optional.of(testUser));
        // First call: key not found (race window); save throws; second lookup finds it
        when(recordRepository.findByCreatedBy_UserIdAndIdempotencyKey(1L, key))
                .thenReturn(Optional.empty())       // initial check
                .thenReturn(Optional.of(existing)); // after DataIntegrityViolationException
        when(recordRepository.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("duplicate key"));

        RecordResponse result = recordService.createRecord(req, 1L);

        assertEquals(99L, result.getRecordId());
        // Only one save attempt was made
        verify(recordRepository, times(1)).saveAndFlush(any(FinancialRecord.class));
    }

    /**
     * Given: a record with the correct version.
     * When: update is called with the matching version.
     * Then: the update succeeds and auditLog is called.
     */
    @Test
    void testUpdateRecord_matchingVersion_succeeds() {
        FinancialRecord record = new FinancialRecord();
        record.setRecordId(5L);
        record.setVersion(2L);
        record.setCreatedBy(testUser);
        record.setAmount(BigDecimal.valueOf(100));
        record.setCategory("Food");

        when(recordRepository.findByIdAndIsDeletedFalse(5L)).thenReturn(Optional.of(record));
        when(recordRepository.save(any())).thenReturn(record);

        UpdateRecordRequest req = new UpdateRecordRequest();
        req.setVersion(2L); // matches DB version
        req.setAmount(BigDecimal.valueOf(200));
        req.setType("EXPENSE");
        req.setCategory("Transport");
        req.setRecordDate(LocalDate.of(2026, 8, 18));

        RecordResponse result = recordService.updateRecord(5L, req, 1L);

        assertNotNull(result);
        verify(auditLogService).record(eq(1L), eq("UPDATE_RECORD"), eq("RECORD"), eq(5L), anyString());
    }

    /**
     * Given: record ID that does not exist.
     * When: updateRecord is called.
     * Then: ResourceNotFoundException is thrown.
     */
    @Test
    void testUpdateRecord_nonExistentId_throwsResourceNotFoundException() {
        when(recordRepository.findByIdAndIsDeletedFalse(999L)).thenReturn(Optional.empty());

        UpdateRecordRequest req = new UpdateRecordRequest();
        req.setVersion(0L);

        assertThrows(ResourceNotFoundException.class,
                () -> recordService.updateRecord(999L, req, 1L));
    }

    @Test
    void testIdempotentCreate_sameKeySamePayload_returnsExistingRecord() {
        String key = "ABC";
        FinancialRecord existing = new FinancialRecord();
        existing.setRecordId(100L);
        existing.setIdempotencyKey(key);
        existing.setCreatedBy(testUser);
        existing.setAmount(BigDecimal.valueOf(50));
        existing.setType("INCOME");
        existing.setCategory("Salary");
        existing.setRecordDate(LocalDate.of(2026, 8, 18));
        
        CreateRecordRequest req = new CreateRecordRequest();
        req.setIdempotencyKey(key);
        req.setAmount(BigDecimal.valueOf(50));
        req.setType("INCOME");
        req.setCategory("Salary");
        req.setRecordDate(LocalDate.of(2026, 8, 18));

        when(recordRepository.findByCreatedBy_UserIdAndIdempotencyKey(1L, key))
                .thenReturn(Optional.of(existing));

        RecordResponse result = recordService.createRecord(req, 1L);

        assertEquals(100L, result.getRecordId());
        verify(recordRepository, never()).saveAndFlush(any());
    }

    @Test
    void testIdempotentCreate_sameKeyDifferentPayload_throwsException() {
        String key = "ABC";
        FinancialRecord existing = new FinancialRecord();
        existing.setRecordId(100L);
        existing.setIdempotencyKey(key);
        existing.setCreatedBy(testUser);
        existing.setAmount(BigDecimal.valueOf(50));
        existing.setType("INCOME");
        existing.setCategory("Salary");
        existing.setRecordDate(LocalDate.of(2026, 8, 18));
        
        CreateRecordRequest req = new CreateRecordRequest();
        req.setIdempotencyKey(key);
        req.setAmount(BigDecimal.valueOf(100)); // Different amount
        req.setType("INCOME");
        req.setCategory("Salary");
        req.setRecordDate(LocalDate.of(2026, 8, 18));

        when(recordRepository.findByCreatedBy_UserIdAndIdempotencyKey(1L, key))
                .thenReturn(Optional.of(existing));

        assertThrows(IllegalArgumentException.class, () -> recordService.createRecord(req, 1L));
        verify(recordRepository, never()).saveAndFlush(any());
    }

    @Test
    void testIdempotentCreate_crossUserCollision_succeedsIndependently() {
        String key = "ABC";
        CreateRecordRequest req = new CreateRecordRequest();
        req.setIdempotencyKey(key);
        req.setAmount(BigDecimal.valueOf(50));
        req.setType("INCOME");
        req.setCategory("Salary");
        req.setRecordDate(LocalDate.of(2026, 8, 18));

        User userB = new User();
        userB.setUserId(2L);
        userB.setName("User B");

        when(userRepository.findById(1L)).thenReturn(Optional.of(testUser));
        when(userRepository.findById(2L)).thenReturn(Optional.of(userB));
        
        when(recordRepository.findByCreatedBy_UserIdAndIdempotencyKey(1L, key))
                .thenReturn(Optional.empty());
        when(recordRepository.findByCreatedBy_UserIdAndIdempotencyKey(2L, key))
                .thenReturn(Optional.empty());

        FinancialRecord saved1 = new FinancialRecord();
        saved1.setRecordId(101L);
        saved1.setCreatedBy(testUser);
        saved1.setAmount(req.getAmount());
        
        FinancialRecord saved2 = new FinancialRecord();
        saved2.setRecordId(102L);
        saved2.setCreatedBy(userB);
        saved2.setAmount(req.getAmount());

        when(recordRepository.saveAndFlush(any(FinancialRecord.class))).thenAnswer(invocation -> {
            FinancialRecord f = invocation.getArgument(0);
            if (f != null && f.getCreatedBy() != null && f.getCreatedBy().getUserId().equals(1L)) {
                return saved1;
            }
            return saved2;
        });

        RecordResponse result1 = recordService.createRecord(req, 1L);
        RecordResponse result2 = recordService.createRecord(req, 2L);

        assertEquals(101L, result1.getRecordId());
        assertEquals(102L, result2.getRecordId());
        verify(recordRepository, times(2)).saveAndFlush(any(FinancialRecord.class));
    }

    @Test
    void testDeleteRecord_auditsAction() {
        FinancialRecord record = new FinancialRecord();
        record.setRecordId(5L);
        record.setCreatedBy(testUser);

        when(recordRepository.findByIdAndIsDeletedFalse(5L)).thenReturn(Optional.of(record));

        recordService.deleteRecord(5L, 1L);

        assertTrue(record.isDeleted());
        verify(recordRepository).save(record);
        verify(auditLogService).record(1L, "DELETE_RECORD", "RECORD", 5L, "Soft deleted record");
    }
}
