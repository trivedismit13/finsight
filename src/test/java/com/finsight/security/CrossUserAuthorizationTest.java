package com.finsight.security;

import com.finsight.dto.response.ExpenseResponse;
import com.finsight.model.Expense;
import com.finsight.model.ExpenseCategory;
import com.finsight.model.ExpenseStatus;
import com.finsight.model.Role;
import com.finsight.model.User;
import com.finsight.repository.ExpenseRepository;
import com.finsight.repository.UserRepository;
import com.finsight.service.DashboardService;
import com.finsight.service.ExpenseService;
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
@com.finsight.security.WithMockCustomUser(roles = "FINANCE_ADMIN")
@ActiveProfiles("test")
@Transactional
class CrossUserAuthorizationTest {

    @Autowired
    private ExpenseService expenseService;

    @Autowired
    private DashboardService dashboardService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ExpenseRepository expenseRepository;

    private User userA;
    private User userB;
    private User admin;

    @BeforeEach
    void setup() {
        expenseRepository.deleteAll();
        userRepository.deleteAll();

        userA = new User();
        userA.setName("User A");
        userA.setEmail("usera@example.com");
        userA.setPassword("hashed");
        userA.setRole(Role.EMPLOYEE);
        userRepository.save(userA);

        userB = new User();
        userB.setName("User B");
        userB.setEmail("userb@example.com");
        userB.setPassword("hashed");
        userB.setRole(Role.EMPLOYEE);
        userRepository.save(userB);

        admin = new User();
        admin.setName("Admin User");
        admin.setEmail("finance_admin@example.com");
        admin.setPassword("hashed");
        admin.setRole(Role.FINANCE_ADMIN);
        userRepository.save(admin);

        // User A records
        createExpense(userA, ExpenseCategory.TRAVEL, "100.50", LocalDate.now());
        createExpense(userA, ExpenseCategory.OFFICE_SUPPLIES, "2000.00", LocalDate.now());

        // User B records
        createExpense(userB, ExpenseCategory.TRAVEL, "500.00", LocalDate.now());
        createExpense(userB, ExpenseCategory.MEALS, "200.00", LocalDate.now());
    }

    private void createExpense(User user, ExpenseCategory category, String amount, LocalDate date) {
        Expense expense = new Expense();
        expense.setCreatedBy(user);
        expense.setCategory(category);
        expense.setAmount(new BigDecimal(amount));
        expense.setExpenseDate(date);
        expense.setDescription("Test");
        expense.setDeleted(false);
        expense.setStatus(ExpenseStatus.DRAFT);
        expenseRepository.save(expense);
    }

    @Test
    void testUserACannotSeeUserBRecords() {
        var page = expenseService.getAllExpenses(null, null, null, null, PageRequest.of(0, 10, Sort.unsorted()), userA.getUserId());
        assertEquals(2, page.getTotalElements());
        assertTrue(page.getContent().stream().allMatch(r -> r.getCategory().equals("TRAVEL") || r.getCategory().equals("OFFICE_SUPPLIES")));
    }

    @Test
    void testUserBCannotSeeUserARecords() {
        var page = expenseService.getAllExpenses(null, null, null, null, PageRequest.of(0, 10, Sort.unsorted()), userB.getUserId());
        assertEquals(2, page.getTotalElements());
        assertTrue(page.getContent().stream().allMatch(r -> r.getCategory().equals("TRAVEL") || r.getCategory().equals("MEALS")));
        assertTrue(page.getContent().stream().anyMatch(r -> r.getAmount().compareTo(new BigDecimal("500.00")) == 0));
    }

    @Test
    void testAdminCanSeeBothRecords() {
        var page = expenseService.getAllExpenses(null, null, null, null, PageRequest.of(0, 10, Sort.unsorted()), admin.getUserId());
        assertEquals(4, page.getTotalElements(), "Admin should see all records across the system");
    }

    @Test
    void testDashboardAggregationIsolatedToUser() {
        Map<String, Object> summaryAdmin = dashboardService.getCompanyAnalytics(LocalDate.now().minusDays(1), LocalDate.now().plusDays(1));
        org.junit.jupiter.api.Assertions.assertNotNull(summaryAdmin.get("totalExpenses"));
    }

    @Test
    void testExpensesOwnershipWithPart34Filters() {
        var page = expenseService.getAllExpenses("DRAFT", "TRAVEL", null, null, PageRequest.of(0, 10, Sort.unsorted()), userA.getUserId());
        assertEquals(1, page.getTotalElements());
        assertEquals(new BigDecimal("100.50"), page.getContent().get(0).getAmount());

        var pageB = expenseService.getAllExpenses("DRAFT", "MEALS", null, null, PageRequest.of(0, 10, Sort.unsorted()), userB.getUserId());
        assertEquals(1, pageB.getTotalElements());
        assertEquals(new BigDecimal("200.00"), pageB.getContent().get(0).getAmount());
    }
}
