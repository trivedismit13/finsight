package com.finsight.service;

import com.finsight.exception.InvalidRequestException;
import com.finsight.model.Expense;
import com.finsight.model.ReportJob;
import com.finsight.model.User;
import com.finsight.repository.ExpenseRepository;
import com.finsight.repository.ReportJobRepository;
import com.finsight.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ReportExportServiceTest {

    @Mock
    private ReportJobRepository reportJobRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private ExpenseRepository expenseRepository;
    @Mock
    private NotificationDispatcherService notificationDispatcherService;
    @Mock
    private org.springframework.context.ApplicationEventPublisher eventPublisher;

    @InjectMocks
    private ReportExportService reportExportService;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        org.springframework.test.util.ReflectionTestUtils.setField(reportExportService, "self", reportExportService);
        org.springframework.test.util.ReflectionTestUtils.setField(reportExportService, "meterRegistry", new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
        org.springframework.test.util.ReflectionTestUtils.setField(reportExportService, "reportExecutor", (java.util.concurrent.Executor) Runnable::run);
        reportExportService.init(); // Initialize temp directory
    }

    @Test
    void testRequestReport_ValidPeriod() {
        User user = new User();
        user.setUserId(1L);

        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        
        ReportJob mockJob = new ReportJob();
        mockJob.setJobId(123L);
        mockJob.setPeriod("2026-08");
        mockJob.setRequestedBy(user);
        when(reportJobRepository.save(any())).thenReturn(mockJob);
        when(reportJobRepository.findById(123L)).thenReturn(Optional.of(mockJob));

        Long jobId = reportExportService.requestReport(1L, "2026-08");
        assertEquals(123L, jobId);
    }

    @Test
    void testRequestReport_InvalidPeriod_ThrowsException() {
        assertThrows(InvalidRequestException.class, () -> reportExportService.requestReport(1L, "2026-13"));
        assertThrows(InvalidRequestException.class, () -> reportExportService.requestReport(1L, "invalid"));
        assertThrows(InvalidRequestException.class, () -> reportExportService.requestReport(1L, "../2026-08"));
    }

    @Test
    void testPathTraversalGetReportPath_ThrowsSecurityException() {
        assertThrows(SecurityException.class, () -> reportExportService.getReportPath("../etc/passwd"));
    }

    @Test
    void testCsvInjectionAndEscaping() throws Exception {
        // Use reflection to call the private method or just test via full integration/mock
        java.lang.reflect.Method method = ReportExportService.class.getDeclaredMethod("escapeCsvAndPreventInjection", String.class);
        method.setAccessible(true);
        
        // Test Formula Neutralization
        assertEquals("'=SUM(A1:B1)", method.invoke(reportExportService, "=SUM(A1:B1)"));
        assertEquals("'+CMD()", method.invoke(reportExportService, "+CMD()"));
        assertEquals("'-CMD()", method.invoke(reportExportService, "-CMD()"));
        assertEquals("'@CMD()", method.invoke(reportExportService, "@CMD()"));
        
        // Test CSV Escaping
        assertEquals("\"Hello, World\"", method.invoke(reportExportService, "Hello, World"));
        assertEquals("\"Line1\nLine2\"", method.invoke(reportExportService, "Line1\nLine2"));
        assertEquals("\"Say \"\"Hello\"\"\"", method.invoke(reportExportService, "Say \"Hello\""));
    }

    @Test
    void testSuccessfulCompletionEndToEnd() throws Exception {
        User user = new User();
        user.setUserId(1L);
        user.setRole(com.finsight.model.Role.FINANCE_ADMIN);

        when(userRepository.findById(1L)).thenReturn(Optional.of(user));

        ReportJob mockJob = new ReportJob();
        mockJob.setJobId(999L);
        mockJob.setPeriod("2026-08");
        mockJob.setRequestedBy(user);
        mockJob.setStatus("PENDING");

        when(reportJobRepository.save(any())).thenReturn(mockJob);
        when(reportJobRepository.findById(999L)).thenReturn(Optional.of(mockJob));
        ReportJob job = new ReportJob();
        job.setJobId(999L);
        job.setStatus("PROCESSING");
        job.setPeriod("2026-08");
        User testUser = new User();
        testUser.setUserId(1L);
        job.setRequestedBy(testUser);
        when(reportJobRepository.findById(999L)).thenReturn(java.util.Optional.of(job));
        when(reportJobRepository.claimJob(999L)).thenReturn(1);
        when(reportJobRepository.updateJobState(eq(999L), eq("COMPLETED"), any(), any(), any())).thenReturn(1);

        Expense rec = new Expense();
        rec.setExpenseId(10L);
        rec.setExpenseDate(LocalDate.of(2026, 8, 5));
        rec.setCategory(com.finsight.model.ExpenseCategory.OTHER);
        rec.setAmount(new BigDecimal("100.00"));
        rec.setCurrency("USD");
        rec.setStatus(com.finsight.model.ExpenseStatus.DRAFT);
        rec.setDescription("Normal \"quotes\" and , comma");

        org.springframework.data.domain.Slice<Expense> slice = 
            new org.springframework.data.domain.SliceImpl<>(List.of(rec));
        when(expenseRepository.findAllByDateRange(any(), any(), any())).thenReturn(slice);

        // We can manually call handleReportJobCreated
        reportExportService.handleReportJobCreated(new com.finsight.service.ReportJobCreatedEvent(999L));

        // Use Awaitility to wait until updateJobState is called
        org.awaitility.Awaitility.await().atMost(5, java.util.concurrent.TimeUnit.SECONDS).untilAsserted(() -> {
            verify(reportJobRepository).updateJobState(eq(999L), eq("COMPLETED"), any(), any(), any());
        });

        // Read the CSV file
        java.nio.file.Path reportPath = reportExportService.getReportPath("report-999.csv");
        assertTrue(Files.exists(reportPath));
        List<String> lines = Files.readAllLines(reportPath);
        
        assertEquals(2, lines.size());
        String expectedHeader = "Expense ID,Date,Status,Category,Amount,Currency,Description";
        String fileContent = String.join("\n", lines);
        assertTrue(fileContent.contains("10,2026-08-05,DRAFT,OTHER,100.00,USD"), "CSV should contain data row start");
        assertTrue(fileContent.contains("\"Normal \"\"quotes\"\" and , comma\""), "CSV should contain escaped description");

    }
}
