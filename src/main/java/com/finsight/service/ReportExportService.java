package com.finsight.service;

import com.finsight.model.Expense;
import com.finsight.model.ReportJob;
import com.finsight.model.Role;
import com.finsight.model.User;
import com.finsight.repository.ExpenseRepository;
import com.finsight.repository.ReportJobRepository;
import com.finsight.repository.UserRepository;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;

import java.io.BufferedWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Counter;

@Slf4j
@Service
@RequiredArgsConstructor
public class ReportExportService {
    private final ReportJobRepository reportJobRepository;
    private final UserRepository userRepository;
    private final ExpenseRepository expenseRepository;
    private final NotificationDispatcherService notificationDispatcherService;
    private final org.springframework.context.ApplicationEventPublisher eventPublisher;
    private final MeterRegistry meterRegistry;

    @org.springframework.beans.factory.annotation.Qualifier("reportExecutor")
    private final Executor reportExecutor;

    private final Path reportBaseDir = Paths.get(System.getProperty("java.io.tmpdir"), "finsight-reports").normalize();

    private Counter reportSuccessCounter;
    private Counter reportFailureCounter;

    // Self injection for @Transactional method calls
    @org.springframework.beans.factory.annotation.Autowired
    @org.springframework.context.annotation.Lazy
    private ReportExportService self;

    @jakarta.annotation.PostConstruct
    public void init() {
        this.reportSuccessCounter = meterRegistry.counter("reports.completed.total");
        this.reportFailureCounter = meterRegistry.counter("reports.failed.total");
        
        try {
            Files.createDirectories(reportBaseDir);
        } catch (Exception e) {
            log.error("Failed to create report base directory", e);
        }
        recoverStuckJobs();
    }

    public void recoverStuckJobs() {
        java.util.List<ReportJob> stuckJobs = reportJobRepository.findByStatus("PROCESSING");
        for (ReportJob job : stuckJobs) {
            log.warn("Recovering stuck report job {}", job.getJobId());
            markFailed(job, job.getJobId(), "System crashed during processing");
        }
    }

    @org.springframework.transaction.annotation.Transactional
    @PreAuthorize("hasRole('FINANCE_ADMIN')")
    public Long requestReport(Long userId, String period) {
        // Validation: Verify it is a true YearMonth (not just regex)
        try {
            YearMonth.parse(period, DateTimeFormatter.ofPattern("yyyy-MM"));
        } catch (DateTimeParseException e) {
            throw new com.finsight.exception.InvalidRequestException("Invalid period format. Must be a valid YYYY-MM.");
        }

        User user = userRepository.findById(userId).orElseThrow();
        ReportJob job = new ReportJob();
        job.setRequestedBy(user);
        job.setPeriod(period);
        job.setStatus("PENDING");
        
        // Generate correlationId if not present in MDC
        String correlationId = org.slf4j.MDC.get(com.finsight.config.MdcLoggingFilter.CORRELATION_ID_KEY);
        if (correlationId == null) correlationId = java.util.UUID.randomUUID().toString();
        job.setCorrelationId(correlationId);
        
        job = reportJobRepository.save(job);

        final Long jobId = job.getJobId();
        eventPublisher.publishEvent(new ReportJobCreatedEvent(jobId));
        log.info("Report job {} created for userId={} period={}", jobId, userId, period);
        return jobId;
    }

    @org.springframework.transaction.event.TransactionalEventListener(phase = org.springframework.transaction.event.TransactionPhase.AFTER_COMMIT)
    public void handleReportJobCreated(ReportJobCreatedEvent event) {
        log.info("Report job {} committed, submitting to worker", event.getJobId());
        reportExecutor.execute(() -> generateReport(event.getJobId()));
    }

    private void generateReport(Long jobId) {
        // Atomic claim
        if (!self.claimReportJob(jobId)) {
            log.info("Report job {} could not be claimed (already processing or failed)", jobId);
            return;
        }

        ReportJob job = null;
        try {
            job = reportJobRepository.findById(jobId).orElseThrow();
            
            // Set correlationId in MDC (traceId is set by MdcTaskDecorator)
            org.slf4j.MDC.put(com.finsight.config.MdcLoggingFilter.CORRELATION_ID_KEY, job.getCorrelationId());
            org.slf4j.MDC.put(com.finsight.config.MdcLoggingFilter.USER_ID_KEY, String.valueOf(job.getRequestedBy().getUserId()));
            
            log.info("Starting report generation for job {}", jobId);

            // Generate filename based entirely on server-generated ID
            String filename = "report-" + jobId + ".csv";
            Path filePath = reportBaseDir.resolve(filename).normalize();
            
            // Store the path so it can be cleaned up in the catch block if needed
            final Path finalFilePath = filePath;

            // Defense in depth canonical path check
            if (!filePath.startsWith(reportBaseDir)) {
                throw new SecurityException("Path traversal attempt detected internally");
            }

            // Eagerly fetch necessary data inside a minimal transaction/method, but here we can just use the repository to fetch what we need.
            // Since we need to know if the user is ADMIN, let's fetch the User directly.
            User requestedBy = userRepository.findById(job.getRequestedBy().getUserId()).orElseThrow();
            Role userRole = requestedBy.getRole();
            java.time.YearMonth ym = java.time.YearMonth.parse(job.getPeriod());
            java.time.LocalDate startDate = ym.atDay(1);
            java.time.LocalDate endDate = ym.plusMonths(1).atDay(1);

            try (BufferedWriter writer = Files.newBufferedWriter(filePath)) {
                writer.write("Expense ID,Date,Status,Category,Amount,Currency,Description\n");

                org.springframework.data.domain.Pageable pageable = org.springframework.data.domain.PageRequest.of(
                        0, 500, org.springframework.data.domain.Sort.by("expenseDate", "expenseId").ascending());
                org.springframework.data.domain.Slice<Expense> slice;

                do {
                    slice = self.fetchAndWriteReportChunk(startDate, endDate, pageable, writer);
                    pageable = slice.nextPageable();
                } while (slice.hasNext());
            }

            int updated = self.updateJobState(jobId, "COMPLETED", LocalDateTime.now(), filename, null);
            if (updated > 0) {
                log.info("Report job {} completed", jobId);
                reportSuccessCounter.increment();
                notificationDispatcherService.enqueueNotification(
                        job.getRequestedBy().getUserId(),
                        "REPORT_READY",
                        "Report for period " + job.getPeriod() + " is ready. JobId=" + jobId
                );
            } else {
                log.warn("Stale worker attempted to mark report job {} as COMPLETED", jobId);
            }
        } catch (Exception e) {
            log.error("Report job {} failed: {}", jobId, e.getMessage(), e);
            markFailed(job, jobId, e.getMessage());
            
            // Cleanup partial file if it was created
            try {
                Path filePath = reportBaseDir.resolve("report-" + jobId + ".csv").normalize();
                if (Files.exists(filePath)) {
                    Files.delete(filePath);
                    log.info("Cleaned up partial report file for job {}", jobId);
                }
            } catch (Exception ex) {
                log.error("Failed to clean up partial report file for job {}: {}", jobId, ex.getMessage());
            }
        }
    }

    @org.springframework.transaction.annotation.Transactional(readOnly = true, propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    public org.springframework.data.domain.Slice<Expense> fetchAndWriteReportChunk(
            java.time.LocalDate startDate, java.time.LocalDate endDate,
            org.springframework.data.domain.Pageable pageable, java.io.Writer writer) throws java.io.IOException {

        org.springframework.data.domain.Slice<Expense> slice = expenseRepository.findAllByDateRange(startDate, endDate, pageable);

        for (Expense record : slice.getContent()) {
            writer.write(String.format("%s,%s,%s,%s,%s,%s,%s\n",
                    escapeCsv(String.valueOf(record.getExpenseId())),
                    escapeCsv(record.getExpenseDate().toString()),
                    escapeCsv(record.getStatus().name()),
                    escapeCsvAndPreventInjection(record.getCategory().name()),
                    escapeCsv(record.getAmount().toPlainString()),
                    escapeCsv(record.getCurrency()),
                    escapeCsvAndPreventInjection(record.getDescription())
            ));
        }
        return slice;
    }

    @org.springframework.transaction.annotation.Transactional
    public boolean claimReportJob(Long jobId) {
        return reportJobRepository.claimJob(jobId) > 0;
    }

    @org.springframework.transaction.annotation.Transactional
    public int updateJobState(Long jobId, String status, LocalDateTime completedAt, String filePath, String failureReason) {
        return reportJobRepository.updateJobState(jobId, status, completedAt, filePath, failureReason);
    }

    private String escapeCsvAndPreventInjection(String value) {
        if (value == null) return "";
        
        // Prevent CSV Injection
        if (value.startsWith("=") || value.startsWith("+") || value.startsWith("-") || value.startsWith("@")) {
            value = "'" + value;
        }
        
        return escapeCsv(value);
    }

    private String escapeCsv(String value) {
        if (value == null) return "";
        if (value.contains(",") || value.contains("\"") || value.contains("\n") || value.contains("\r")) {
            value = value.replace("\"", "\"\"");
            return "\"" + value + "\"";
        }
        return value;
    }

    private void markFailed(ReportJob job, Long jobId, String reason) {
        try {
            int updated = self.updateJobState(jobId, "FAILED", LocalDateTime.now(), null, reason);
            if (updated == 0) {
                log.warn("Stale worker attempted to mark report job {} as FAILED", jobId);
            } else {
                reportFailureCounter.increment();
            }
        } catch (Exception ex) {
            log.error("Could not mark report job {} as FAILED: {}", jobId, ex.getMessage());
        }
    }

    @Scheduled(cron = "0 0 * * * *") // Run hourly
    public void cleanupOldReports() {
        log.info("Running report cleanup task...");
        try (Stream<Path> paths = Files.list(reportBaseDir)) {
            paths.filter(Files::isRegularFile).forEach(path -> {
                try {
                    long lastModifiedTime = Files.getLastModifiedTime(path).toMillis();
                    if (System.currentTimeMillis() - lastModifiedTime > TimeUnit.HOURS.toMillis(24)) {
                        Files.delete(path);
                        log.info("Cleaned up old report: {}", path.getFileName());
                    }
                } catch (Exception e) {
                    log.error("Failed to cleanup report: {}", path.getFileName(), e);
                }
            });
        } catch (Exception e) {
            log.error("Failed to list report directory for cleanup", e);
        }
    }

    public Path getReportPath(String filename) {
        Path resolved = reportBaseDir.resolve(filename).normalize();
        if (!resolved.startsWith(reportBaseDir)) {
            throw new SecurityException("Path traversal attempt");
        }
        return resolved;
    }

    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    @PreAuthorize("hasRole('FINANCE_ADMIN')")
    public ReportJob getReportStatus(Long jobId, Long userId) {
        ReportJob job = reportJobRepository.findById(jobId)
                .orElseThrow(() -> new com.finsight.exception.ResourceNotFoundException("Job not found: " + jobId));

        // Since only FINANCE_ADMIN can call this method via @PreAuthorize, we don't need additional ownership checks
        
        return job;
    }

    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    @PreAuthorize("hasRole('FINANCE_ADMIN')")
    public org.springframework.core.io.Resource getReportDownloadResource(Long jobId, Long userId) {
        ReportJob job = reportJobRepository.findById(jobId)
                .orElseThrow(() -> new com.finsight.exception.ResourceNotFoundException("Job not found: " + jobId));

        // Since only FINANCE_ADMIN can call this method via @PreAuthorize, we don't need additional ownership checks

        if (!"COMPLETED".equals(job.getStatus()) || job.getFilePath() == null) {
            throw new com.finsight.exception.InvalidRequestException("Report is not ready for download");
        }

        try {
            Path filePath = getReportPath(job.getFilePath());
            if (!Files.exists(filePath)) {
                throw new com.finsight.exception.ResourceNotFoundException("Report file is no longer available on the server");
            }
            return new org.springframework.core.io.FileSystemResource(filePath.toFile());
        } catch (SecurityException ex) {
            throw new com.finsight.exception.ResourceNotFoundException("Report not found");
        }
    }
}
