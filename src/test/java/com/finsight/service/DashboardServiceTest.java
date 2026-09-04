package com.finsight.service;

import com.finsight.repository.DashboardRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

public class DashboardServiceTest {

    @Mock
    private DashboardRepository dashboardRepository;

    @InjectMocks
    private DashboardService dashboardService;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
    }

    @Test
    void testGetCompanyAnalytics() {
        when(dashboardRepository.getTotalCompanyExpenses(any(), any())).thenReturn(new BigDecimal("1000"));
        List<Object[]> breakdown = new ArrayList<>();
        breakdown.add(new Object[]{"MEALS", new BigDecimal("1000")});
        when(dashboardRepository.getCompanyCategoryBreakdown(any(), any())).thenReturn(breakdown);

        Map<String, Object> result = dashboardService.getCompanyAnalytics(LocalDate.now(), LocalDate.now());
        assertEquals(new BigDecimal("1000"), result.get("totalExpenses"));
    }
}
