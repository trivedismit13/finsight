package com.finsight.service;

import com.finsight.model.CategoryBudget;
import com.finsight.model.FinancialRecord;
import com.finsight.model.User;
import com.finsight.repository.CategoryBudgetRepository;
import com.finsight.repository.DashboardRepository;
import com.finsight.repository.FinancialRecordRepository;
import com.finsight.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@com.finsight.security.WithMockCustomUser(roles = "ADMIN")
@Transactional
@org.springframework.test.context.ActiveProfiles("test")
public class DashboardServiceTest {

    @Autowired
    private DashboardService dashboardService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private FinancialRecordRepository recordRepository;

    @Autowired
    private CategoryBudgetRepository budgetRepository;

    private User testUser;

    @BeforeEach
    void setup() {
        recordRepository.deleteAll();
        budgetRepository.deleteAll();
        userRepository.deleteAll();

        testUser = new User();
        testUser.setName("Analytics User");
        testUser.setEmail("analytics@example.com");
        testUser.setPassword("hash");
        testUser.setRole(com.finsight.model.Role.ADMIN);
        testUser.setActive(true);
        testUser = userRepository.save(testUser);

        // 1. Income: $1000 in August
        FinancialRecord inc1 = new FinancialRecord();
        inc1.setAmount(new BigDecimal("1000.00"));
        inc1.setType("INCOME");
        inc1.setCategory("Salary");
        inc1.setRecordDate(LocalDate.of(2026, 8, 1));
        inc1.setCreatedBy(testUser);
        recordRepository.save(inc1);

        // 2. Income: $500 in August
        FinancialRecord inc2 = new FinancialRecord();
        inc2.setAmount(new BigDecimal("500.00"));
        inc2.setType("INCOME");
        inc2.setCategory("Bonus");
        inc2.setRecordDate(LocalDate.of(2026, 8, 15));
        inc2.setCreatedBy(testUser);
        recordRepository.save(inc2);

        // 3. Expense: $400 in August (Food)
        FinancialRecord exp1 = new FinancialRecord();
        exp1.setAmount(new BigDecimal("400.00"));
        exp1.setType("EXPENSE");
        exp1.setCategory("Food");
        exp1.setRecordDate(LocalDate.of(2026, 8, 5));
        exp1.setCreatedBy(testUser);
        recordRepository.save(exp1);

        // 4. Expense: $200 in August (Travel)
        FinancialRecord exp2 = new FinancialRecord();
        exp2.setAmount(new BigDecimal("200.00"));
        exp2.setType("EXPENSE");
        exp2.setCategory("Travel");
        exp2.setRecordDate(LocalDate.of(2026, 8, 20));
        exp2.setCreatedBy(testUser);
        recordRepository.save(exp2);

        // 5. Expense: $100 in August (Food) BUT DELETED
        FinancialRecord expDel = new FinancialRecord();
        expDel.setAmount(new BigDecimal("100.00"));
        expDel.setType("EXPENSE");
        expDel.setCategory("Food");
        expDel.setRecordDate(LocalDate.of(2026, 8, 10));
        expDel.setCreatedBy(testUser);
        expDel.setDeleted(true);
        recordRepository.save(expDel);

        // Budget for Food in August: $500
        CategoryBudget b1 = new CategoryBudget();
        b1.setCategory("Food");
        b1.setMonthYear("2026-08");
        b1.setBudgetAmount(new BigDecimal("500.00"));
        b1.setCreatedBy(testUser);
        budgetRepository.save(b1);

        // Budget for Utilities in August: $150 (Zero actual spending)
        CategoryBudget b2 = new CategoryBudget();
        b2.setCategory("Utilities");
        b2.setMonthYear("2026-08");
        b2.setBudgetAmount(new BigDecimal("150.00"));
        b2.setCreatedBy(testUser);
        budgetRepository.save(b2);

        // Budget for Travel in August: $200 (Exact match -> ON_TRACK)
        CategoryBudget b3 = new CategoryBudget();
        b3.setCategory("Travel");
        b3.setMonthYear("2026-08");
        b3.setBudgetAmount(new BigDecimal("200.00"));
        b3.setCreatedBy(testUser);
        budgetRepository.save(b3);

        // Expense: $150 in August (Entertainment)
        FinancialRecord exp3 = new FinancialRecord();
        exp3.setAmount(new BigDecimal("150.00"));
        exp3.setType("EXPENSE");
        exp3.setCategory("Entertainment");
        exp3.setRecordDate(LocalDate.of(2026, 8, 25));
        exp3.setCreatedBy(testUser);
        recordRepository.save(exp3);

        // Budget for Entertainment in August: $50 (OVER_BUDGET)
        CategoryBudget b4 = new CategoryBudget();
        b4.setCategory("Entertainment");
        b4.setMonthYear("2026-08");
        b4.setBudgetAmount(new BigDecimal("50.00"));
        b4.setCreatedBy(testUser);
        budgetRepository.save(b4);
    }

    @Test
    void testGetSummary() {
        Map<String, Object> summary = dashboardService.getSummary(LocalDate.of(2026, 1, 1), LocalDate.of(2027, 1, 1), testUser.getUserId(), false);
        // Total Income: 1000 + 500 = 1500
        // Total Expense: 400 (Food) + 200 (Travel) + 150 (Entertainment) = 750 (deleted 100 is excluded)
        // Balance: 1500 - 750 = 750
        assertEquals(0, new BigDecimal("1500.00").compareTo((BigDecimal) summary.get("totalIncome")));
        assertEquals(0, new BigDecimal("750.00").compareTo((BigDecimal) summary.get("totalExpense")));
        assertEquals(0, new BigDecimal("750.00").compareTo((BigDecimal) summary.get("balance")));
    }

    @Test
    void testGetCategoryBreakdown() {
        List<Map<String, Object>> breakdown = dashboardService.getCategoryBreakdown(LocalDate.of(2026, 1, 1), LocalDate.of(2027, 1, 1), testUser.getUserId(), false);
        // Expect Food: 400, Travel: 200, Entertainment: 150
        assertEquals(3, breakdown.size());
        boolean foodFound = false;
        boolean travelFound = false;
        boolean entFound = false;
        for (Map<String, Object> map : breakdown) {
            if ("Food".equals(map.get("category"))) {
                assertEquals(0, new BigDecimal("400.00").compareTo((BigDecimal) map.get("total")));
                foodFound = true;
            } else if ("Travel".equals(map.get("category"))) {
                assertEquals(0, new BigDecimal("200.00").compareTo((BigDecimal) map.get("total")));
                travelFound = true;
            } else if ("Entertainment".equals(map.get("category"))) {
                assertEquals(0, new BigDecimal("150.00").compareTo((BigDecimal) map.get("total")));
                entFound = true;
            }
        }
        assertTrue(foodFound);
        assertTrue(travelFound);
        assertTrue(entFound);
    }

    @Test
    void testGetMonthlyTrend() {
        List<Map<String, Object>> trend = dashboardService.getMonthlyTrend(LocalDate.of(2026, 1, 1), LocalDate.of(2027, 1, 1), testUser.getUserId(), false);
        assertEquals(1, trend.size());
        Map<String, Object> aug = trend.get(0);
        assertEquals("2026-08", aug.get("month"));
        assertEquals(0, new BigDecimal("1500.00").compareTo((BigDecimal) aug.get("income")));
        assertEquals(0, new BigDecimal("750.00").compareTo((BigDecimal) aug.get("expense")));
    }

    @Test
    void testGetTopExpenseCategories() {
        List<Map<String, Object>> top = dashboardService.getTopExpenseCategories(LocalDate.of(2026, 1, 1), LocalDate.of(2027, 1, 1), testUser.getUserId(), false);
        assertEquals(3, top.size());
        // Food should be first (400 > 200 > 150)
        assertEquals("Food", top.get(0).get("category"));
        assertEquals(0, new BigDecimal("400.00").compareTo((BigDecimal) top.get(0).get("total")));
        assertEquals("Travel", top.get(1).get("category"));
        assertEquals(0, new BigDecimal("200.00").compareTo((BigDecimal) top.get(1).get("total")));
        assertEquals("Entertainment", top.get(2).get("category"));
        assertEquals(0, new BigDecimal("150.00").compareTo((BigDecimal) top.get(2).get("total")));
    }

    @Test
    void testGetDailyAverage() {
        Map<String, Object> daily = dashboardService.getDailyAverage(LocalDate.of(2026, 1, 1), LocalDate.of(2027, 1, 1), testUser.getUserId(), false);
        // 3 distinct days of expense: Aug 5, Aug 20, Aug 25 (deleted Aug 10 doesn't count)
        // Total expense = 750
        // Daily average = 750 / 3 = 250
        assertEquals(0, new BigDecimal("750.00").compareTo((BigDecimal) daily.get("totalExpense")));
        assertEquals(3L, daily.get("activeDays"));
        assertEquals(0, new BigDecimal("250.00").compareTo((BigDecimal) daily.get("dailyAverage")));
    }

    @Test
    void testGetBudgetVsActual() {
        List<Map<String, Object>> bva = dashboardService.getBudgetVsActual(testUser.getUserId(), false);
        assertEquals(4, bva.size());
        
        boolean foodFound = false;
        boolean utilFound = false;
        boolean travelFound = false;
        boolean entFound = false;
        
        for (Map<String, Object> map : bva) {
            if ("Food".equals(map.get("category"))) {
                assertEquals(0, new BigDecimal("500.00").compareTo((BigDecimal) map.get("budgetLimit")));
                assertEquals(0, new BigDecimal("400.00").compareTo((BigDecimal) map.get("actualSpent")));
                assertEquals("UNDER_BUDGET", map.get("status"));
                foodFound = true;
            } else if ("Utilities".equals(map.get("category"))) {
                assertEquals(0, new BigDecimal("150.00").compareTo((BigDecimal) map.get("budgetLimit")));
                assertEquals(0, new BigDecimal("0.00").compareTo((BigDecimal) map.get("actualSpent")));
                assertEquals("UNDER_BUDGET", map.get("status"));
                utilFound = true;
            } else if ("Travel".equals(map.get("category"))) {
                assertEquals(0, new BigDecimal("200.00").compareTo((BigDecimal) map.get("budgetLimit")));
                assertEquals(0, new BigDecimal("200.00").compareTo((BigDecimal) map.get("actualSpent")));
                assertEquals("ON_TRACK", map.get("status"));
                travelFound = true;
            } else if ("Entertainment".equals(map.get("category"))) {
                assertEquals(0, new BigDecimal("50.00").compareTo((BigDecimal) map.get("budgetLimit")));
                assertEquals(0, new BigDecimal("150.00").compareTo((BigDecimal) map.get("actualSpent")));
                assertEquals("OVER_BUDGET", map.get("status"));
                entFound = true;
            }
        }
        assertTrue(foodFound);
        assertTrue(utilFound);
        assertTrue(travelFound);
        assertTrue(entFound);
    }

    @Test
    void testDashboardBoundaryDates() {
        // Excluded: Before boundary
        FinancialRecord jul31 = new FinancialRecord();
        jul31.setAmount(new BigDecimal("10.00"));
        jul31.setType("INCOME");
        jul31.setCategory("Salary");
        jul31.setRecordDate(LocalDate.of(2026, 7, 31));
        jul31.setCreatedBy(testUser);
        recordRepository.save(jul31);

        // Included: Start boundary
        FinancialRecord aug1 = new FinancialRecord();
        aug1.setAmount(new BigDecimal("20.00"));
        aug1.setType("INCOME");
        aug1.setCategory("Salary");
        aug1.setRecordDate(LocalDate.of(2026, 8, 1));
        aug1.setCreatedBy(testUser);
        recordRepository.save(aug1);

        // Included: Middle
        FinancialRecord aug15 = new FinancialRecord();
        aug15.setAmount(new BigDecimal("30.00"));
        aug15.setType("INCOME");
        aug15.setCategory("Salary");
        aug15.setRecordDate(LocalDate.of(2026, 8, 15));
        aug15.setCreatedBy(testUser);
        recordRepository.save(aug15);

        // Excluded: End boundary
        FinancialRecord sep1 = new FinancialRecord();
        sep1.setAmount(new BigDecimal("40.00"));
        sep1.setType("INCOME");
        sep1.setCategory("Salary");
        sep1.setRecordDate(LocalDate.of(2026, 9, 1));
        sep1.setCreatedBy(testUser);
        recordRepository.save(sep1);

        Map<String, Object> summary = dashboardService.getSummary(LocalDate.of(2026, 8, 1), LocalDate.of(2026, 9, 1), testUser.getUserId(), false);
        
        // Base income from setup() in Aug is 1500 (1000 + 500)
        // Added income included: 20 (Aug 1) + 30 (Aug 15) = 50
        // Total Income should be 1550
        assertEquals(0, new BigDecimal("1550.00").compareTo((BigDecimal) summary.get("totalIncome")),
            "Summary should strictly INCLUDE Aug 1 and EXCLUDE Sep 1, excluding July 31 and including Aug 15");
    }
}



