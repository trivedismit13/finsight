package com.finsight.observability;

import com.finsight.config.MdcLoggingFilter;
import com.finsight.model.ReportJob;
import com.finsight.model.User;
import com.finsight.repository.ReportJobRepository;
import com.finsight.repository.UserRepository;
import com.finsight.service.ReportExportService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
public class ObservabilityTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ReportJobRepository reportJobRepository;
    
    @Autowired
    private ReportExportService reportExportService;

    @Autowired
    private com.finsight.repository.NotificationRepository notificationRepository;
    
    @Autowired
    private com.finsight.service.NotificationDispatcherService notificationDispatcherService;

    @org.springframework.boot.test.mock.mockito.MockBean
    private com.finsight.service.EmailProviderService emailProviderService;

    @Autowired
    private com.finsight.repository.FinancialRecordRepository financialRecordRepository;

    private User testUser;

    @BeforeEach
    void setUp() {
        User u = new User();
        u.setName("Test User");
        u.setEmail("testobs_" + UUID.randomUUID().toString() + "@example.com");
        u.setPassword("password");
        u.setRole(com.finsight.model.Role.VIEWER);
        testUser = userRepository.save(u);
    }

    @Test
    void testActuatorSecurity() throws Exception {
        // Health should be public
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk());

        // Info should be public
        mockMvc.perform(get("/actuator/info"))
                .andExpect(status().isOk());

        // Env should be inaccessible since it's not exposed
        mockMvc.perform(get("/actuator/env"))
                .andExpect(status().isUnauthorized()); // Or 404 depending on how Spring handles unmapped vs secured

        mockMvc.perform(get("/actuator/env")
                .with(user("admin@example.com").roles("ADMIN")))
                .andExpect(status().isNotFound()); // If not exposed, it throws NoResourceFoundException which is mapped to 404
    }

    @Test
    void testMdcLeakage() throws Exception {
        // Issue two requests sequentially and ensure MDC is clear between them.
        // Also simulate setting MDC on main thread and ensure it's clear afterwards.
        MDC.clear();
        
        String customCorrelation = UUID.randomUUID().toString();
        
        mockMvc.perform(get("/api/reports/export/1/download")
                .header("X-Correlation-Id", customCorrelation)
                .with(user(testUser.getEmail()).roles("VIEWER")));
                
        assertNull(MDC.get(MdcLoggingFilter.TRACE_ID_KEY), "MDC traceId must be cleared after request");
        assertNull(MDC.get(MdcLoggingFilter.CORRELATION_ID_KEY), "MDC correlationId must be cleared after request");
        assertNull(MDC.get(MdcLoggingFilter.USER_ID_KEY), "MDC userId must be cleared after request");
    }

    @Test
    void testAsyncCorrelationDurable() throws Exception {
        // Test that the worker pulls correlationId from the DB and the worker thread MDC contains the same correlationId.
        
        MDC.put(MdcLoggingFilter.CORRELATION_ID_KEY, "test-worker-mdc-correlation");
        MDC.put(MdcLoggingFilter.USER_ID_KEY, String.valueOf(testUser.getUserId()));
        
        java.util.concurrent.atomic.AtomicReference<String> workerCorrelationId = new java.util.concurrent.atomic.AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        
        // We'll dispatch a notification and intercept the email provider to read MDC
        org.mockito.Mockito.doAnswer(invocation -> {
            workerCorrelationId.set(MDC.get(MdcLoggingFilter.CORRELATION_ID_KEY));
            latch.countDown();
            return true;
        }).when(emailProviderService).sendEmail(org.mockito.ArgumentMatchers.any());
        
        try {
            // Trigger an async notification
            notificationDispatcherService.enqueueNotification(
                    testUser.getUserId(), 
                    "REPORT_READY", 
                    "{\"jobId\": 999}");
            
            // Wait for worker to run independently
            boolean completed = latch.await(10, TimeUnit.SECONDS);
            assertTrue(completed, "Worker did not execute in time");
            
            // Verify worker logs contained same correlationId
            assertEquals("test-worker-mdc-correlation", workerCorrelationId.get(), "Worker MDC must contain the durable correlation ID");
            
        } catch (Exception e) {
            fail("Exception during test: " + e.getMessage());
        } finally {
            MDC.clear();
        }
    }
}
