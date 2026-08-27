package com.finsight.repository;

import com.finsight.model.FinancialRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

@Repository
public interface FinancialRecordRepository extends JpaRepository<FinancialRecord, Long>, JpaSpecificationExecutor<FinancialRecord> {

    @org.springframework.data.jpa.repository.EntityGraph(attributePaths = {"createdBy"})
    Optional<FinancialRecord> findByCreatedBy_UserIdAndIdempotencyKey(Long userId, String idempotencyKey);
    
    @Query("SELECT r FROM FinancialRecord r WHERE r.recordId = :id AND r.isDeleted = false")
    Optional<FinancialRecord> findByIdAndIsDeletedFalse(@Param("id") Long id);
    
    java.util.List<FinancialRecord> findByIsDeletedFalse();

    /**
     * Calculates total EXPENSE spending for a given category and month-year period.
     * Used by BudgetService to check if spending exceeds the configured budget.
     * month_year format: "YYYY-MM" e.g. "2026-08"
     */
    @Query("SELECT COALESCE(SUM(r.amount), 0) FROM FinancialRecord r " +
           "WHERE r.category = :category " +
           "AND r.type = 'EXPENSE' " +
           "AND r.isDeleted = false " +
           "AND r.recordDate >= :startDate AND r.recordDate < :endDateExclusive")
    BigDecimal sumExpensesByCategoryAndDateRange(@Param("category") String category,
                                                 @Param("startDate") java.time.LocalDate startDate,
                                                 @Param("endDateExclusive") java.time.LocalDate endDateExclusive);

    @Query("SELECT r FROM FinancialRecord r WHERE r.createdBy.userId = :userId AND r.isDeleted = false AND r.recordDate >= :startDate AND r.recordDate < :endDateExclusive")
    org.springframework.data.domain.Slice<FinancialRecord> findByUserAndDateRange(@Param("userId") Long userId, @Param("startDate") java.time.LocalDate startDate, @Param("endDateExclusive") java.time.LocalDate endDateExclusive, org.springframework.data.domain.Pageable pageable);

    @Query("SELECT r FROM FinancialRecord r WHERE r.isDeleted = false AND r.recordDate >= :startDate AND r.recordDate < :endDateExclusive")
    org.springframework.data.domain.Slice<FinancialRecord> findByDateRange(@Param("startDate") java.time.LocalDate startDate, @Param("endDateExclusive") java.time.LocalDate endDateExclusive, org.springframework.data.domain.Pageable pageable);
}
