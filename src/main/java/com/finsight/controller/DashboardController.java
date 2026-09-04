package com.finsight.controller;

import com.finsight.service.DashboardService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import java.util.Map;
import java.time.LocalDate;
import org.springframework.web.bind.annotation.RequestParam;

@RestController
@RequestMapping("/api/analytics")
@RequiredArgsConstructor
public class DashboardController {
    private final DashboardService dashboardService;

    @GetMapping("/company")
    public Map<String, Object> getCompanyAnalytics(
            @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) LocalDate endDate) {
        return dashboardService.getCompanyAnalytics(resolveStartDate(startDate), resolveEndDate(endDate));
    }

    private LocalDate resolveStartDate(LocalDate provided) {
        return provided != null ? provided : LocalDate.now().minusYears(1).withDayOfMonth(1);
    }

    private LocalDate resolveEndDate(LocalDate provided) {
        return provided != null ? provided : LocalDate.now().plusMonths(1).withDayOfMonth(1);
    }
}
