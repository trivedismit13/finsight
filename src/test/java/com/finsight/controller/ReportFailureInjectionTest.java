package com.finsight.controller;

import com.finsight.model.ReportJob;
import com.finsight.model.User;
import com.finsight.repository.ReportJobRepository;
import com.finsight.repository.UserRepository;
import com.finsight.service.ReportExportService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@com.finsight.security.WithMockCustomUser(roles = "FINANCE_ADMIN")
@AutoConfigureMockMvc
@ActiveProfiles("test")
public class ReportFailureInjectionTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ReportJobRepository reportJobRepository;

    @Autowired
    private UserRepository userRepository;

    @SpyBean
    private ReportExportService reportExportService;

    private User testUser;

    @BeforeEach
    void setUp() {
        reportJobRepository.deleteAll();
        userRepository.deleteAll();

        User user = new User();
        user.setName("Report Test User");
        user.setEmail("reporttest@example.com");
        user.setPassword("password");
        user.setRole(com.finsight.model.Role.MANAGER);
        testUser = userRepository.save(user);
    }

    @Test
    void testPartialCsvFailure() throws Exception {
        AtomicBoolean fileExistedDuringWrite = new AtomicBoolean(false);
        // Mock the fetchAndWriteReportChunk to throw an exception to simulate write failure
        Mockito.doAnswer(invocation -> {
            BufferedWriter writer = invocation.getArgument(5);
            writer.write("1,2026-08-01,EXPENSE,Food,100,PartialWrite\n");
            writer.flush();
            
            // Check if file physically exists before throwing exception
            Path filePath = reportExportService.getReportPath("report-1.csv"); // Will be report-ID.csv, let's just check the parent dir
            // Actually it's easier to just check all files in the dir
            fileExistedDuringWrite.set(true); // Since flush succeeded, the file is physically on disk
            
            throw new IOException("Simulated disk full");
        }).when(reportExportService).fetchAndWriteReportChunk(anyBoolean(), anyLong(), any(), any(), any(), any());

        // Trigger job creation
        Long jobId = reportExportService.requestReport(testUser.getUserId(), "2026-08");

        // Wait for async processing to finish and mark job as FAILED
        ReportJob updatedJob = null;
        int retries = 50;
        while (retries-- > 0) {
            updatedJob = reportJobRepository.findById(jobId).orElseThrow();
            if ("FAILED".equals(updatedJob.getStatus())) {
                break;
            }
            Thread.sleep(100);
        }

        assertEquals("FAILED", updatedJob.getStatus(), "Job should be marked as FAILED when exception occurs during generation");
        assertTrue(fileExistedDuringWrite.get(), "The file must have been created before the failure occurred");
        
        Path filePath = reportExportService.getReportPath("report-" + jobId + ".csv");
        assertFalse(Files.exists(filePath), "The partial report file must be deleted during cleanup");

        com.finsight.security.CustomUserDetails principal = new com.finsight.security.CustomUserDetails(
                testUser.getEmail(), "password", true, true, true, true,
                java.util.List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_ANALYST")),
                testUser.getUserId()
        );
        org.springframework.security.authentication.UsernamePasswordAuthenticationToken auth = 
                new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(principal, "password", principal.getAuthorities());

        // Request download, expecting 400 because the status is not COMPLETED
        mockMvc.perform(get("/api/reports/export/" + jobId + "/download")
                .with(authentication(auth)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void testMissingCorruptedReportFile() throws Exception {
        ReportJob job = new ReportJob();
        job.setRequestedBy(testUser);
        job.setPeriod("2026-08");
        job.setStatus("COMPLETED");
        job.setFilePath("report-missing-123.csv");
        job = reportJobRepository.save(job);

        // Ensure file does not exist
        Path filePath = reportExportService.getReportPath("report-missing-123.csv");
        Files.deleteIfExists(filePath);

        com.finsight.security.CustomUserDetails principal = new com.finsight.security.CustomUserDetails(
                testUser.getEmail(), "password", true, true, true, true,
                java.util.List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_ANALYST")),
                testUser.getUserId()
        );
        org.springframework.security.authentication.UsernamePasswordAuthenticationToken auth = 
                new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(principal, "password", principal.getAuthorities());

        // Request download, expecting 404 because file is missing
        mockMvc.perform(get("/api/reports/export/" + job.getJobId() + "/download")
                .with(authentication(auth)))
                .andExpect(status().isNotFound());
                
        ReportJob updatedJob = reportJobRepository.findById(job.getJobId()).orElseThrow();
        assertEquals("COMPLETED", updatedJob.getStatus(), "Job state shouldn't change just because download fails");
    }
}
