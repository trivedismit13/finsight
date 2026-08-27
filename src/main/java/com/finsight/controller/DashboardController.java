package com.finsight.controller;

import com.finsight.service.DashboardService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import java.util.List;
import java.util.Map;
import java.time.LocalDate;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;

@RestController
@RequiredArgsConstructor
public class DashboardController {
    private final DashboardService dashboardService;
    private Long resolveUserId(UserDetails principal) {
        if (principal instanceof com.finsight.security.CustomUserDetails) {
            return ((com.finsight.security.CustomUserDetails) principal).getUserId();
        }
        throw new RuntimeException("Authenticated user not found or invalid type");
    }

    private boolean resolveIsAdmin(UserDetails principal) {
        return principal.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"));
    }

    @GetMapping("/summary")
    public Map<String, Object> getSummary(
            @RequestParam(required = false) LocalDate startDate,
            @RequestParam(required = false) LocalDate endDate,
            @AuthenticationPrincipal UserDetails principal) {
        return dashboardService.getSummary(resolveStartDate(startDate), resolveEndDate(endDate), resolveUserId(principal), resolveIsAdmin(principal));
    }

    @GetMapping("/categories")
    public List<Map<String, Object>> getCategoryBreakdown(
            @RequestParam(required = false) LocalDate startDate,
            @RequestParam(required = false) LocalDate endDate,
            @AuthenticationPrincipal UserDetails principal) {
        return dashboardService.getCategoryBreakdown(resolveStartDate(startDate), resolveEndDate(endDate), resolveUserId(principal), resolveIsAdmin(principal));
    }

    @GetMapping("/trend/monthly")
    public List<Map<String, Object>> getMonthlyTrend(
            @RequestParam(required = false) LocalDate startDate,
            @RequestParam(required = false) LocalDate endDate,
            @AuthenticationPrincipal UserDetails principal) {
        return dashboardService.getMonthlyTrend(resolveStartDate(startDate), resolveEndDate(endDate), resolveUserId(principal), resolveIsAdmin(principal));
    }

    @GetMapping("/trend/weekly")
    public List<Map<String, Object>> getWeeklyTrend(
            @RequestParam(required = false) LocalDate startDate,
            @RequestParam(required = false) LocalDate endDate,
            @AuthenticationPrincipal UserDetails principal) {
        return dashboardService.getWeeklyTrend(resolveStartDate(startDate), resolveEndDate(endDate), resolveUserId(principal), resolveIsAdmin(principal));
    }

    @GetMapping("/categories/top")
    public List<Map<String, Object>> getTopExpenseCategories(
            @RequestParam(required = false) LocalDate startDate,
            @RequestParam(required = false) LocalDate endDate,
            @AuthenticationPrincipal UserDetails principal) {
        return dashboardService.getTopExpenseCategories(resolveStartDate(startDate), resolveEndDate(endDate), resolveUserId(principal), resolveIsAdmin(principal));
    }

    @GetMapping("/trend/income-vs-expense")
    public List<Map<String, Object>> getIncomeVsExpenseTrend(
            @RequestParam(required = false) LocalDate startDate,
            @RequestParam(required = false) LocalDate endDate,
            @AuthenticationPrincipal UserDetails principal) {
        return dashboardService.getIncomeVsExpenseTrend(resolveStartDate(startDate), resolveEndDate(endDate), resolveUserId(principal), resolveIsAdmin(principal));
    }

    @GetMapping("/daily-average")
    public Map<String, Object> getDailyAverage(
            @RequestParam(required = false) LocalDate startDate,
            @RequestParam(required = false) LocalDate endDate,
            @AuthenticationPrincipal UserDetails principal) {
        return dashboardService.getDailyAverage(resolveStartDate(startDate), resolveEndDate(endDate), resolveUserId(principal), resolveIsAdmin(principal));
    }

    @GetMapping("/categories/over-time")
    public List<Map<String, Object>> getCategoryOverTime(
            @RequestParam(required = false) LocalDate startDate,
            @RequestParam(required = false) LocalDate endDate,
            @AuthenticationPrincipal UserDetails principal) {
        return dashboardService.getCategoryOverTime(resolveStartDate(startDate), resolveEndDate(endDate), resolveUserId(principal), resolveIsAdmin(principal));
    }

    @GetMapping("/budget-vs-actual")
    public List<Map<String, Object>> getBudgetVsActual(@AuthenticationPrincipal UserDetails principal) {
        return dashboardService.getBudgetVsActual(resolveUserId(principal), resolveIsAdmin(principal));
    }

    @GetMapping("/user-activity")
    public List<Map<String, Object>> getUserActivity(
            @RequestParam(required = false) LocalDate startDate,
            @RequestParam(required = false) LocalDate endDate,
            @AuthenticationPrincipal UserDetails principal) {
        return dashboardService.getUserActivity(resolveStartDate(startDate), resolveEndDate(endDate), resolveUserId(principal), resolveIsAdmin(principal));
    }

    private LocalDate resolveStartDate(LocalDate provided) {
        return provided != null ? provided : LocalDate.now().minusYears(1).withDayOfMonth(1);
    }

    private LocalDate resolveEndDate(LocalDate provided) {
        // We use exclusive end date in queries (< endDate)
        return provided != null ? provided : LocalDate.now().plusMonths(1).withDayOfMonth(1);
    }
}
