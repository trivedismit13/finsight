package com.finsight.security;

import com.finsight.dto.response.RecordResponse;
import com.finsight.model.FinancialRecord;
import com.finsight.model.Role;
import com.finsight.model.User;
import com.finsight.repository.FinancialRecordRepository;
import com.finsight.repository.UserRepository;
import com.finsight.service.DashboardService;
import com.finsight.service.FinancialRecordService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@com.finsight.security.WithMockCustomUser(roles = "ADMIN")
@ActiveProfiles("test")
@Transactional
class CrossUserAuthorizationTest {

    @Autowired
    private FinancialRecordService recordService;

    @Autowired
    private DashboardService dashboardService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private FinancialRecordRepository recordRepository;

    private User userA;
    private User userB;
    private User admin;

    @BeforeEach
    void setup() {
        recordRepository.deleteAll();
        userRepository.deleteAll();

        userA = new User();
        userA.setName("User A");
        userA.setEmail("usera@example.com");
        userA.setPassword("hashed");
        userA.setRole(Role.VIEWER);
        userRepository.save(userA);

        userB = new User();
        userB.setName("User B");
        userB.setEmail("userb@example.com");
        userB.setPassword("hashed");
        userB.setRole(Role.VIEWER);
        userRepository.save(userB);

        admin = new User();
        admin.setName("Admin User");
        admin.setEmail("admin@example.com");
        admin.setPassword("hashed");
        admin.setRole(Role.ADMIN);
        userRepository.save(admin);

        // User A records
        createRecord(userA, "EXPENSE", "Food", "100.00", LocalDate.now());
        createRecord(userA, "EXPENSE", "Rent", "1000.00", LocalDate.now());

        // User B records
        createRecord(userB, "EXPENSE", "Travel", "500.00", LocalDate.now());
        createRecord(userB, "EXPENSE", "Food", "200.00", LocalDate.now());
    }

    private void createRecord(User user, String type, String category, String amount, LocalDate date) {
        FinancialRecord record = new FinancialRecord();
        record.setCreatedBy(user);
        record.setType(type);
        record.setCategory(category);
        record.setAmount(new BigDecimal(amount));
        record.setRecordDate(date);
        record.setDescription("Test");
        record.setDeleted(false);
        recordRepository.save(record);
    }

    @Test
    void testUserACannotSeeUserBRecords() {
        var page = recordService.getAllRecords(null, null, null, null, PageRequest.of(0, 10, Sort.unsorted()), userA.getUserId(), false);
        assertEquals(2, page.getTotalElements());
        assertTrue(page.getContent().stream().allMatch(r -> r.getCategory().equals("Food") || r.getCategory().equals("Rent")));
    }

    @Test
    void testUserBCannotSeeUserARecords() {
        var page = recordService.getAllRecords(null, null, null, null, PageRequest.of(0, 10, Sort.unsorted()), userB.getUserId(), false);
        assertEquals(2, page.getTotalElements());
        assertTrue(page.getContent().stream().allMatch(r -> r.getCategory().equals("Travel") || r.getCategory().equals("Food")));
        assertTrue(page.getContent().stream().anyMatch(r -> r.getAmount().compareTo(new BigDecimal("500.00")) == 0));
    }

    @Test
    void testAdminCanSeeBothRecords() {
        var page = recordService.getAllRecords(null, null, null, null, PageRequest.of(0, 10, Sort.unsorted()), admin.getUserId(), true);
        assertEquals(4, page.getTotalElements(), "Admin should see all records across the system");
    }

    @Test
    void testDashboardAggregationIsolatedToUser() {
        // User A summary
        Map<String, Object> summaryA = dashboardService.getSummary(LocalDate.now().minusDays(1), LocalDate.now().plusDays(1), userA.getUserId(), false);
        assertEquals(new BigDecimal("1100.00"), summaryA.get("totalExpense"));

        // User B summary
        Map<String, Object> summaryB = dashboardService.getSummary(LocalDate.now().minusDays(1), LocalDate.now().plusDays(1), userB.getUserId(), false);
        assertEquals(new BigDecimal("700.00"), summaryB.get("totalExpense"));

        // Admin summary
        Map<String, Object> summaryAdmin = dashboardService.getSummary(LocalDate.now().minusDays(1), LocalDate.now().plusDays(1), admin.getUserId(), true);
        assertEquals(new BigDecimal("1800.00"), summaryAdmin.get("totalExpense"), "Admin should aggregate over the entire system");
    }

    @Test
    void testFinancialRecordsOwnershipWithPart34Filters() {
        // Test ownership + type filter
        var page = recordService.getAllRecords("EXPENSE", "Food", null, null, PageRequest.of(0, 10, Sort.unsorted()), userA.getUserId(), false);
        assertEquals(1, page.getTotalElements());
        assertEquals(new BigDecimal("100.00"), page.getContent().get(0).getAmount());

        // Test ownership + category filter for User B
        var pageB = recordService.getAllRecords("EXPENSE", "Food", null, null, PageRequest.of(0, 10, Sort.unsorted()), userB.getUserId(), false);
        assertEquals(1, pageB.getTotalElements());
        assertEquals(new BigDecimal("200.00"), pageB.getContent().get(0).getAmount());
    }
}
