package com.finsight.service;

import com.finsight.repository.DashboardRepository;
import com.finsight.repository.CategoryBudgetRepository;
import com.finsight.model.CategoryBudget;
import java.time.LocalDate;
import java.util.Collections;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class DashboardService {
    
    private final DashboardRepository dashboardRepository;
    private final CategoryBudgetRepository budgetRepository;

    @Transactional(readOnly = true)
    @PreAuthorize("hasAnyRole('VIEWER', 'ANALYST', 'ADMIN')")
    public Map<String, Object> getSummary(java.time.LocalDate startDate, java.time.LocalDate endDate, Long actorId, boolean isAdmin) {
        List<Object[]> results = dashboardRepository.getSummary(startDate, endDate, actorId, isAdmin);
        Object[] result = results.isEmpty() ? new Object[]{0, 0} : results.get(0);
        Map<String, Object> map = new HashMap<>();
        BigDecimal income = toBigDecimal(result[0]);
        BigDecimal expense = toBigDecimal(result[1]);
        map.put("totalIncome", income);
        map.put("totalExpense", expense);
        map.put("balance", income.subtract(expense));
        return map;
    }

    @Transactional(readOnly = true)
    @PreAuthorize("hasAnyRole('VIEWER', 'ANALYST', 'ADMIN')")
    public List<Map<String, Object>> getCategoryBreakdown(java.time.LocalDate startDate, java.time.LocalDate endDate, Long actorId, boolean isAdmin) {
        return dashboardRepository.getCategoryBreakdown(startDate, endDate, actorId, isAdmin).stream().map(row -> {
            Map<String, Object> map = new HashMap<>();
            map.put("category", row[0]);
            map.put("total", toBigDecimal(row[1]));
            return map;
        }).collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    @PreAuthorize("hasAnyRole('ANALYST', 'ADMIN')")
    public List<Map<String, Object>> getMonthlyTrend(java.time.LocalDate startDate, java.time.LocalDate endDate, Long actorId, boolean isAdmin) {
        return dashboardRepository.getMonthlyTrend(startDate, endDate, actorId, isAdmin).stream().map(row -> {
            Map<String, Object> map = new HashMap<>();
            map.put("month", row[0]);
            map.put("income", toBigDecimal(row[1]));
            map.put("expense", toBigDecimal(row[2]));
            return map;
        }).collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    @PreAuthorize("hasAnyRole('ANALYST', 'ADMIN')")
    public List<Map<String, Object>> getWeeklyTrend(java.time.LocalDate startDate, java.time.LocalDate endDate, Long actorId, boolean isAdmin) {
        return dashboardRepository.getWeeklyTrend(startDate, endDate, actorId, isAdmin).stream().map(row -> {
            Map<String, Object> map = new HashMap<>();
            map.put("week", row[0]);
            map.put("income", toBigDecimal(row[1]));
            map.put("expense", toBigDecimal(row[2]));
            return map;
        }).collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    @PreAuthorize("hasAnyRole('ANALYST', 'ADMIN')")
    public List<Map<String, Object>> getTopExpenseCategories(java.time.LocalDate startDate, java.time.LocalDate endDate, Long actorId, boolean isAdmin) {
        return dashboardRepository.getTopExpenseCategories(startDate, endDate, actorId, isAdmin, PageRequest.of(0, 5)).stream().map(row -> {
            Map<String, Object> map = new HashMap<>();
            map.put("category", row[0]);
            map.put("total", toBigDecimal(row[1]));
            return map;
        }).collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    @PreAuthorize("hasAnyRole('ANALYST', 'ADMIN')")
    public List<Map<String, Object>> getIncomeVsExpenseTrend(java.time.LocalDate startDate, java.time.LocalDate endDate, Long actorId, boolean isAdmin) {
        return dashboardRepository.getIncomeVsExpenseTrend(startDate, endDate, actorId, isAdmin).stream().map(row -> {
            Map<String, Object> map = new HashMap<>();
            map.put("date", row[0]);
            map.put("income", toBigDecimal(row[1]));
            map.put("expense", toBigDecimal(row[2]));
            return map;
        }).collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    @PreAuthorize("hasAnyRole('ANALYST', 'ADMIN')")
    public Map<String, Object> getDailyAverage(java.time.LocalDate startDate, java.time.LocalDate endDate, Long actorId, boolean isAdmin) {
        List<Object[]> results = dashboardRepository.getDailyAverage(startDate, endDate, actorId, isAdmin);
        Object[] result = results.isEmpty() ? new Object[]{0, 0L} : results.get(0);
        BigDecimal totalExpense = toBigDecimal(result[0]);
        Long days = result[1] instanceof Number ? ((Number) result[1]).longValue() : 0L;
        
        BigDecimal dailyAverage = BigDecimal.ZERO;
        if (days != null && days > 0) {
            dailyAverage = totalExpense.divide(BigDecimal.valueOf(days), 2, RoundingMode.HALF_UP);
        }
        
        Map<String, Object> map = new HashMap<>();
        map.put("totalExpense", totalExpense);
        map.put("activeDays", days);
        map.put("dailyAverage", dailyAverage);
        return map;
    }

    @Transactional(readOnly = true)
    @PreAuthorize("hasAnyRole('ANALYST', 'ADMIN')")
    public List<Map<String, Object>> getCategoryOverTime(java.time.LocalDate startDate, java.time.LocalDate endDate, Long actorId, boolean isAdmin) {
        return dashboardRepository.getCategoryOverTime(startDate, endDate, actorId, isAdmin).stream().map(row -> {
            Map<String, Object> map = new HashMap<>();
            map.put("category", row[0]);
            map.put("month", row[1]);
            map.put("total", toBigDecimal(row[2]));
            return map;
        }).collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    @PreAuthorize("hasAnyRole('ANALYST', 'ADMIN')")
    public List<Map<String, Object>> getBudgetVsActual(Long actorId, boolean isAdmin) {
        List<CategoryBudget> budgets = budgetRepository.findByUser(actorId, isAdmin, org.springframework.data.domain.Sort.by(org.springframework.data.domain.Sort.Direction.DESC, "monthYear"));
        if (budgets.isEmpty()) return Collections.emptyList();

        LocalDate minDate = LocalDate.MAX;
        LocalDate maxDate = LocalDate.MIN;

        for (CategoryBudget b : budgets) {
            java.time.YearMonth ym = java.time.YearMonth.parse(b.getMonthYear());
            LocalDate start = ym.atDay(1);
            LocalDate end = ym.plusMonths(1).atDay(1);
            if (start.isBefore(minDate)) minDate = start;
            if (end.isAfter(maxDate)) maxDate = end;
        }

        // Fetch actuals for the combined date range
        List<Object[]> actuals = dashboardRepository.getCategoryOverTime(minDate, maxDate, actorId, isAdmin);
        Map<String, BigDecimal> actualMap = new HashMap<>();
        for (Object[] row : actuals) {
            String key = row[0] + "-" + row[1];
            actualMap.put(key, toBigDecimal(row[2]));
        }

        return budgets.stream().map(b -> {
            Map<String, Object> map = new HashMap<>();
            map.put("category", b.getCategory());
            map.put("monthYear", b.getMonthYear());
            
            BigDecimal limit = b.getBudgetAmount();
            BigDecimal actual = actualMap.getOrDefault(b.getCategory() + "-" + b.getMonthYear(), BigDecimal.ZERO);
            
            map.put("budgetLimit", limit);
            map.put("actualSpent", actual);
            
            if (actual.compareTo(limit) > 0) {
                map.put("status", "OVER_BUDGET");
            } else if (actual.compareTo(limit) == 0) {
                map.put("status", "ON_TRACK");
            } else {
                map.put("status", "UNDER_BUDGET");
            }
            return map;
        }).collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    @PreAuthorize("hasAnyRole('ANALYST', 'ADMIN')")
    public List<Map<String, Object>> getUserActivity(java.time.LocalDate startDate, java.time.LocalDate endDate, Long actorId, boolean isAdmin) {
        return dashboardRepository.getUserActivity(startDate, endDate, actorId, isAdmin).stream().map(row -> {
            Map<String, Object> map = new HashMap<>();
            map.put("userName", row[0]);
            map.put("recordCount", row[1] instanceof Number ? ((Number) row[1]).longValue() : 0L);
            return map;
        }).collect(Collectors.toList());
    }

    private BigDecimal toBigDecimal(Object val) {
        if (val == null) return BigDecimal.ZERO;
        if (val instanceof BigDecimal) return (BigDecimal) val;
        if (val instanceof Number) return new BigDecimal(((Number) val).doubleValue()).setScale(2, RoundingMode.HALF_UP);
        return new BigDecimal(val.toString()).setScale(2, RoundingMode.HALF_UP);
    }
}

