package com.finsight.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.finsight.model.AuditLog;
import com.finsight.model.Notification;
import com.finsight.model.Role;
import com.finsight.model.User;
import com.finsight.repository.AuditLogRepository;
import com.finsight.repository.ExpenseRepository;
import com.finsight.repository.NotificationRepository;
import com.finsight.repository.UserRepository;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@org.springframework.test.context.ActiveProfiles("test")
@TestPropertySource(properties = {
    "spring.jpa.properties.hibernate.generate_statistics=true",
    "spring.jpa.properties.hibernate.format_sql=false"
})
class NPlusOneQueryAuditTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ExpenseRepository recordRepository;

    @Autowired
    private AuditLogRepository auditLogRepository;

    @Autowired
    private NotificationRepository notificationRepository;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    private Statistics hibernateStatistics;

    @BeforeEach
    void setup() {
        hibernateStatistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        hibernateStatistics.clear();

        // Create 10 users and 100 records
        for (int i = 1; i <= 10; i++) {
            User u = new User();
            u.setName("User " + i);
            u.setEmail("user" + i + "@example.com");
            u.setPassword("hashedpassword" + i);
            u.setRole(Role.EMPLOYEE);
            u = userRepository.save(u);

            for (int j = 1; j <= 10; j++) {
                com.finsight.model.Expense r = new com.finsight.model.Expense();
                r.setAmount(new BigDecimal("10.00"));
                r.setCategory(com.finsight.model.ExpenseCategory.MEALS);
                r.setExpenseDate(LocalDate.now());
                r.setCreatedBy(u);
                recordRepository.save(r);
            }
            
            // Create Audit log
            AuditLog a = new AuditLog();
            a.setActorUserId(u);
            a.setAction("TEST");
            a.setEntityType("RECORD");
            auditLogRepository.save(a);
            
            // Create Notification
            Notification n = new Notification();
            n.setUserId(u);
            n.setPayload("sensitive payload");
            n.setStatus("DEAD_LETTER");
            n.setDeadLetterReason("Test");
            notificationRepository.save(n);
        }
        
        // flush to DB to avoid pending inserts muddying the query counts
        userRepository.flush();
        recordRepository.flush();
        auditLogRepository.flush();
        notificationRepository.flush();
        
        // CLEAR L1 cache so they must be fetched!
        entityManagerFactory.createEntityManager().clear();
    }

    @Test
    @com.finsight.security.WithMockCustomUser(roles = "FINANCE_ADMIN")
    void testExpensesNPlusOneAndSerialization() throws Exception {
        hibernateStatistics.clear();
        
        mockMvc.perform(get("/api/records?page=0&size=20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content", hasSize(20)))
                .andExpect(jsonPath("$.data.content[0].createdByName", notNullValue()))
                .andExpect(jsonPath("$.data.content[0].password").doesNotExist());
                
        long queryCount = hibernateStatistics.getPrepareStatementCount();
        
        // Expected: 1 data query + 1 count query. Maybe 1 query for authentication or similar.
        // It must absolutely be less than 20 (which would indicate N+1 for the page elements).
        assertTrue(queryCount < 5, "Query count should be O(1), found: " + queryCount);
    }
    
    @Test
    @com.finsight.security.WithMockCustomUser(roles = "FINANCE_ADMIN")
    void testAuditLogsNPlusOneAndSerialization() throws Exception {
        hibernateStatistics.clear();
        
        String responseBody = mockMvc.perform(get("/api/admin/audit"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(10)))
                .andExpect(jsonPath("$[0].actorName", notNullValue()))
                .andExpect(jsonPath("$[0].password").doesNotExist()) // explicit leak check
                .andReturn().getResponse().getContentAsString();
                
        assertTrue(!responseBody.contains("hashedpassword"), "Password must not be in response");

        long queryCount = hibernateStatistics.getPrepareStatementCount();
        assertTrue(queryCount < 5, "Audit log query count should be O(1), found: " + queryCount);
    }

    @Test
    @com.finsight.security.WithMockCustomUser(roles = "FINANCE_ADMIN")
    void testNotificationDlqNPlusOneAndSerialization() throws Exception {
        hibernateStatistics.clear();
        
        String responseBody = mockMvc.perform(get("/api/notifications/dlq"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(10)))
                .andExpect(jsonPath("$.data[0].userId", notNullValue()))
                .andExpect(jsonPath("$.data[0].payload").doesNotExist()) // explicitly excluded in DTO
                .andReturn().getResponse().getContentAsString();
                
        assertTrue(!responseBody.contains("sensitive payload"), "Payload must not be in response");
        assertTrue(!responseBody.contains("hashedpassword"), "Password must not be in response");

        long queryCount = hibernateStatistics.getPrepareStatementCount();
        assertTrue(queryCount < 5, "Notification DLQ query count should be O(1), found: " + queryCount);
    }
}
