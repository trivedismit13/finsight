package com.finsight.repository;

import com.finsight.model.Expense;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

@Repository
public interface ExpenseRepository extends JpaRepository<Expense, Long>, JpaSpecificationExecutor<Expense> {

    @org.springframework.data.jpa.repository.EntityGraph(attributePaths = {"createdBy"})
    Optional<Expense> findByCreatedBy_UserIdAndIdempotencyKey(Long userId, String idempotencyKey);
    
    @Query("SELECT r FROM Expense r WHERE r.expenseId = :id AND r.isDeleted = false")
    Optional<Expense> findByIdAndIsDeletedFalse(@Param("id") Long id);
    
    java.util.List<Expense> findByIsDeletedFalse();

    /**
     * Calculates total EXPENSE spending for a given category and month-year period.
     * Used by BudgetService to check if spending exceeds the configured budget.
     * month_year format: "YYYY-MM" e.g. "2026-08"
     */
    @Query("SELECT COALESCE(SUM(r.amount), 0) FROM Expense r " +
           "WHERE r.category = :category " +
           "AND r.status IN ('APPROVED', 'PROCESSED') " +
           "AND r.isDeleted = false " +
           "AND r.expenseDate >= :startDate AND r.expenseDate <= :endDate")
    BigDecimal sumExpensesByCategoryAndDateRange(@Param("category") com.finsight.model.ExpenseCategory category,
                                                 @Param("startDate") java.time.LocalDate startDate,
                                                 @Param("endDate") java.time.LocalDate endDate);

    @Query("SELECT r FROM Expense r WHERE r.createdBy.manager.userId = :managerId " +
           "AND r.status != 'DRAFT' AND r.isDeleted = false")
    org.springframework.data.domain.Slice<Expense> findTeamExpenses(@Param("managerId") Long managerId, org.springframework.data.domain.Pageable pageable);

    @Query("SELECT r FROM Expense r WHERE r.createdBy.userId = :userId AND r.status IN ('APPROVED', 'PROCESSED') AND r.isDeleted = false AND r.expenseDate >= :startDate AND r.expenseDate <= :endDate")
    org.springframework.data.domain.Slice<Expense> findByUserAndDateRange(@Param("userId") Long userId, @Param("startDate") java.time.LocalDate startDate, @Param("endDate") java.time.LocalDate endDate, org.springframework.data.domain.Pageable pageable);

    @Query("SELECT r FROM Expense r WHERE r.status IN ('APPROVED', 'PROCESSED') AND r.isDeleted = false AND r.expenseDate >= :startDate AND r.expenseDate <= :endDate")
    org.springframework.data.domain.Slice<Expense> findAllByDateRange(@Param("startDate") java.time.LocalDate startDate, @Param("endDate") java.time.LocalDate endDate, org.springframework.data.domain.Pageable pageable);

    @Query("SELECT r FROM Expense r WHERE (r.createdBy.userId = :userId OR r.createdBy.manager.userId = :userId) AND r.status IN ('APPROVED', 'PROCESSED') AND r.isDeleted = false AND r.expenseDate >= :startDate AND r.expenseDate <= :endDate")
    org.springframework.data.domain.Slice<Expense> findTeamByDateRange(@Param("userId") Long userId, @Param("startDate") java.time.LocalDate startDate, @Param("endDate") java.time.LocalDate endDate, org.springframework.data.domain.Pageable pageable);
}
