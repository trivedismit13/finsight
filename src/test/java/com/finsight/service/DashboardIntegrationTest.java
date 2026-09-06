package com.finsight.service;

import com.finsight.model.Expense;
import com.finsight.model.User;
import com.finsight.repository.ExpenseRepository;
import com.finsight.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class DashboardIntegrationTest {

    @Autowired
    private DashboardService dashboardService;

    @Autowired
    private ExpenseRepository expenseRepository;

    @Autowired
    private UserRepository userRepository;

    private User testUser;

    @BeforeEach
    void setup() {
        testUser = new User();
        testUser.setName("Dashboard User");
        testUser.setEmail("dash-" + UUID.randomUUID() + "@example.com");
        testUser.setPassword("pass");
        testUser.setRole(com.finsight.model.Role.EMPLOYEE);
        testUser = userRepository.save(testUser);
    }

    @Test
    @WithMockUser(roles = "FINANCE_ADMIN")
    void testInclusiveDateSemantics() {
        // Create 3 expenses on specific dates
        LocalDate start = LocalDate.of(2026, 8, 1);
        LocalDate end = LocalDate.of(2026, 8, 31);

        // 1. Before start (excluded)
        createExpense(start.minusDays(1), new BigDecimal("10.00"));
        // 2. Exactly on start (included)
        createExpense(start, new BigDecimal("100.00"));
        // 3. Middle (included)
        createExpense(LocalDate.of(2026, 8, 15), new BigDecimal("200.00"));
        // 4. Exactly on end (included)
        createExpense(end, new BigDecimal("400.00"));
        // 5. After end (excluded)
        createExpense(end.plusDays(1), new BigDecimal("1000.00"));

        Map<String, Object> analytics = dashboardService.getCompanyAnalytics(start, end);
        
        // Sum should be 100 + 200 + 400 = 700
        BigDecimal expectedTotal = new BigDecimal("700.00");
        BigDecimal actualTotal = (BigDecimal) analytics.get("totalExpenses");

        assertEquals(expectedTotal.compareTo(actualTotal), 0, "Dashboard should inclusively count boundary dates");
    }

    private void createExpense(LocalDate date, BigDecimal amount) {
        Expense exp = new Expense();
        exp.setAmount(amount);
        exp.setCategory(com.finsight.model.ExpenseCategory.OTHER);
        exp.setExpenseDate(date);
        exp.setCreatedBy(testUser);
        exp.setStatus(com.finsight.model.ExpenseStatus.PROCESSED);
        expenseRepository.save(exp);
    }
}
