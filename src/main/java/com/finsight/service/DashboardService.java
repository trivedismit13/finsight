package com.finsight.service;

import com.finsight.repository.DashboardRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class DashboardService {
    
    private final DashboardRepository dashboardRepository;

    @Transactional(readOnly = true)
    @PreAuthorize("hasAnyRole('FINANCE_ADMIN', 'ADMIN')")
    public Map<String, Object> getCompanyAnalytics(LocalDate startDate, LocalDate endDate) {
        Map<String, Object> map = new HashMap<>();
        BigDecimal total = dashboardRepository.getTotalCompanyExpenses(startDate, endDate);
        
        List<Map<String, Object>> breakdown = dashboardRepository.getCompanyCategoryBreakdown(startDate, endDate).stream().map(row -> {
            Map<String, Object> catMap = new HashMap<>();
            catMap.put("category", row[0].toString());
            catMap.put("total", row[1]);
            return catMap;
        }).collect(Collectors.toList());
        
        map.put("totalExpenses", total);
        map.put("categoryBreakdown", breakdown);
        return map;
    }
}
