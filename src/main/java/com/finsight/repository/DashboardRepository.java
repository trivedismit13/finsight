package com.finsight.repository;

import com.finsight.model.Expense;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;

@Repository
public interface DashboardRepository extends JpaRepository<Expense, Long> {

    @Query("SELECT COALESCE(SUM(r.amount), 0) FROM Expense r WHERE r.isDeleted = false AND r.status IN ('APPROVED', 'PROCESSED') AND r.expenseDate >= :startDate AND r.expenseDate <= :endDate")
    java.math.BigDecimal getTotalCompanyExpenses(@Param("startDate") LocalDate startDate, @Param("endDate") LocalDate endDate);

    @Query("SELECT r.category, SUM(r.amount) FROM Expense r WHERE r.isDeleted = false AND r.status IN ('APPROVED', 'PROCESSED') AND r.expenseDate >= :startDate AND r.expenseDate <= :endDate GROUP BY r.category")
    List<Object[]> getCompanyCategoryBreakdown(@Param("startDate") LocalDate startDate, @Param("endDate") LocalDate endDate);
}
