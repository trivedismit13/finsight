package com.finsight.service;

import com.finsight.model.FinancialRecord;
import com.finsight.model.User;
import com.finsight.repository.AuditLogRepository;
import com.finsight.repository.FinancialRecordRepository;
import com.finsight.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.test.context.ActiveProfiles;
import com.finsight.dto.request.CreateRecordRequest;
import com.finsight.model.Role;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@SpringBootTest
@com.finsight.security.WithMockCustomUser(roles = "ADMIN")
@ActiveProfiles("test")
public class AuditIntegrationTest {

    @Autowired
    private FinancialRecordService financialRecordService;

    @Autowired
    private FinancialRecordRepository financialRecordRepository;

    @Autowired
    private AuditLogRepository auditLogRepository;

    @Autowired
    private UserRepository userRepository;

    @SpyBean
    private AuditLogService auditLogService;

    private User testUser;

    @BeforeEach
    void setUp() {
        financialRecordRepository.deleteAll();
        auditLogRepository.deleteAll();
        userRepository.deleteAll();

        testUser = new User();
        testUser.setName("Audit Test User");
        testUser.setEmail("audit@test.com");
        testUser.setPassword("password");
        testUser.setRole(Role.VIEWER);
        testUser = userRepository.save(testUser);
    }

    @MockBean
    private BudgetService budgetService;

    @Test
    void testAuditRollback_whenBusinessTransactionFails() {
        CreateRecordRequest req = new CreateRecordRequest();
        req.setAmount(new BigDecimal("100.00"));
        req.setCategory("Food");
        req.setType("EXPENSE");
        req.setRecordDate(LocalDate.now());
        req.setIdempotencyKey("TEST_ROLLBACK_KEY");

        // Force a failure in the business logic AFTER audit is called
        doThrow(new RuntimeException("Simulated budget error"))
            .when(budgetService).checkBudgetExceededAfterRecord(anyString(), anyString(), anyLong());
        
        try {
            financialRecordService.createRecord(req, testUser.getUserId());
        } catch (Exception e) {
            // expected
        }

        // Neither record nor audit should exist
        assertEquals(0, financialRecordRepository.count());
        assertEquals(0, auditLogRepository.count());
    }

    @Test
    void testAuditFailure_preventsBusinessTransactionCommit() {
        CreateRecordRequest req = new CreateRecordRequest();
        req.setAmount(new BigDecimal("200.00"));
        req.setCategory("Travel");
        req.setType("EXPENSE");
        req.setRecordDate(LocalDate.now());
        req.setIdempotencyKey("TEST_AUDIT_FAIL_KEY");

        // Force audit service to throw an exception
        doThrow(new RuntimeException("Audit DB Down")).when(auditLogService)
                .record(any(), anyString(), anyString(), any(), anyString());

        try {
            financialRecordService.createRecord(req, testUser.getUserId());
        } catch (Exception e) {
            // expected RuntimeException
        }

        // The financial record should not be saved because the audit failed
        assertEquals(0, financialRecordRepository.count());
        assertEquals(0, auditLogRepository.count());
    }
    @Test
    void testDeleteRecord_auditRollback() {
        FinancialRecord record = new FinancialRecord();
        record.setAmount(new BigDecimal("100.00"));
        record.setCategory("Food");
        record.setType("EXPENSE");
        record.setRecordDate(LocalDate.now());
        record.setCreatedBy(testUser);
        record = financialRecordRepository.save(record);
        
        Long recordId = record.getRecordId();

        doThrow(new RuntimeException("Audit DB Down")).when(auditLogService)
                .record(any(), eq("DELETE_RECORD"), anyString(), any(), anyString());

        try {
            financialRecordService.deleteRecord(recordId, testUser.getUserId());
        } catch (Exception e) {
            // expected RuntimeException
        }

        FinancialRecord dbRecord = financialRecordRepository.findById(recordId).orElseThrow();
        org.junit.jupiter.api.Assertions.assertFalse(dbRecord.isDeleted());
        assertEquals(0, auditLogRepository.count());
    }
}
