package com.finsight.repository;

import com.finsight.model.FinancialRecord;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface DashboardRepository extends JpaRepository<FinancialRecord, Long> {

    @Query("SELECT " +
           "COALESCE(SUM(CASE WHEN r.type = 'INCOME' THEN r.amount ELSE 0 END), 0), " +
           "COALESCE(SUM(CASE WHEN r.type = 'EXPENSE' THEN r.amount ELSE 0 END), 0) " +
           "FROM FinancialRecord r WHERE r.isDeleted = false AND r.recordDate >= :startDate AND r.recordDate < :endDate AND (r.createdBy.userId = :userId OR :isAdmin = true)")
    List<Object[]> getSummary(@org.springframework.data.repository.query.Param("startDate") java.time.LocalDate startDate, @org.springframework.data.repository.query.Param("endDate") java.time.LocalDate endDate, @org.springframework.data.repository.query.Param("userId") Long userId, @org.springframework.data.repository.query.Param("isAdmin") boolean isAdmin);

    @Query("SELECT r.category, SUM(r.amount) " +
           "FROM FinancialRecord r WHERE r.type = 'EXPENSE' AND r.isDeleted = false AND r.recordDate >= :startDate AND r.recordDate < :endDate AND (r.createdBy.userId = :userId OR :isAdmin = true) " +
           "GROUP BY r.category")
    List<Object[]> getCategoryBreakdown(@org.springframework.data.repository.query.Param("startDate") java.time.LocalDate startDate, @org.springframework.data.repository.query.Param("endDate") java.time.LocalDate endDate, @org.springframework.data.repository.query.Param("userId") Long userId, @org.springframework.data.repository.query.Param("isAdmin") boolean isAdmin);

    @Query("SELECT FUNCTION('DATE_FORMAT', r.recordDate, '%Y-%m'), " +
           "SUM(CASE WHEN r.type = 'INCOME' THEN r.amount ELSE 0 END), " +
           "SUM(CASE WHEN r.type = 'EXPENSE' THEN r.amount ELSE 0 END) " +
           "FROM FinancialRecord r WHERE r.isDeleted = false AND r.recordDate >= :startDate AND r.recordDate < :endDate AND (r.createdBy.userId = :userId OR :isAdmin = true) " +
           "GROUP BY FUNCTION('DATE_FORMAT', r.recordDate, '%Y-%m') " +
           "ORDER BY FUNCTION('DATE_FORMAT', r.recordDate, '%Y-%m') ASC")
    List<Object[]> getMonthlyTrend(@org.springframework.data.repository.query.Param("startDate") java.time.LocalDate startDate, @org.springframework.data.repository.query.Param("endDate") java.time.LocalDate endDate, @org.springframework.data.repository.query.Param("userId") Long userId, @org.springframework.data.repository.query.Param("isAdmin") boolean isAdmin);

    @Query("SELECT FUNCTION('YEARWEEK', r.recordDate, 1), " +
           "SUM(CASE WHEN r.type = 'INCOME' THEN r.amount ELSE 0 END), " +
           "SUM(CASE WHEN r.type = 'EXPENSE' THEN r.amount ELSE 0 END) " +
           "FROM FinancialRecord r WHERE r.isDeleted = false AND r.recordDate >= :startDate AND r.recordDate < :endDate AND (r.createdBy.userId = :userId OR :isAdmin = true) " +
           "GROUP BY FUNCTION('YEARWEEK', r.recordDate, 1) " +
           "ORDER BY FUNCTION('YEARWEEK', r.recordDate, 1) ASC")
    List<Object[]> getWeeklyTrend(@org.springframework.data.repository.query.Param("startDate") java.time.LocalDate startDate, @org.springframework.data.repository.query.Param("endDate") java.time.LocalDate endDate, @org.springframework.data.repository.query.Param("userId") Long userId, @org.springframework.data.repository.query.Param("isAdmin") boolean isAdmin);

    @Query("SELECT r.category, SUM(r.amount) as total " +
           "FROM FinancialRecord r WHERE r.type = 'EXPENSE' AND r.isDeleted = false AND r.recordDate >= :startDate AND r.recordDate < :endDate AND (r.createdBy.userId = :userId OR :isAdmin = true) " +
           "GROUP BY r.category ORDER BY total DESC")
    List<Object[]> getTopExpenseCategories(@org.springframework.data.repository.query.Param("startDate") java.time.LocalDate startDate, @org.springframework.data.repository.query.Param("endDate") java.time.LocalDate endDate, @org.springframework.data.repository.query.Param("userId") Long userId, @org.springframework.data.repository.query.Param("isAdmin") boolean isAdmin, Pageable pageable);

    @Query("SELECT FUNCTION('DATE_FORMAT', r.recordDate, '%Y-%m-%d'), " +
           "SUM(CASE WHEN r.type = 'INCOME' THEN r.amount ELSE 0 END), " +
           "SUM(CASE WHEN r.type = 'EXPENSE' THEN r.amount ELSE 0 END) " +
           "FROM FinancialRecord r WHERE r.isDeleted = false AND r.recordDate >= :startDate AND r.recordDate < :endDate AND (r.createdBy.userId = :userId OR :isAdmin = true) " +
           "GROUP BY FUNCTION('DATE_FORMAT', r.recordDate, '%Y-%m-%d') " +
           "ORDER BY FUNCTION('DATE_FORMAT', r.recordDate, '%Y-%m-%d') ASC")
    List<Object[]> getIncomeVsExpenseTrend(@org.springframework.data.repository.query.Param("startDate") java.time.LocalDate startDate, @org.springframework.data.repository.query.Param("endDate") java.time.LocalDate endDate, @org.springframework.data.repository.query.Param("userId") Long userId, @org.springframework.data.repository.query.Param("isAdmin") boolean isAdmin);

    @Query("SELECT COALESCE(SUM(r.amount), 0), COUNT(DISTINCT r.recordDate) " +
           "FROM FinancialRecord r WHERE r.type = 'EXPENSE' AND r.isDeleted = false AND r.recordDate >= :startDate AND r.recordDate < :endDate AND (r.createdBy.userId = :userId OR :isAdmin = true)")
    List<Object[]> getDailyAverage(@org.springframework.data.repository.query.Param("startDate") java.time.LocalDate startDate, @org.springframework.data.repository.query.Param("endDate") java.time.LocalDate endDate, @org.springframework.data.repository.query.Param("userId") Long userId, @org.springframework.data.repository.query.Param("isAdmin") boolean isAdmin);

    @Query("SELECT r.category, FUNCTION('DATE_FORMAT', r.recordDate, '%Y-%m'), SUM(r.amount) " +
           "FROM FinancialRecord r WHERE r.type = 'EXPENSE' AND r.isDeleted = false AND r.recordDate >= :startDate AND r.recordDate < :endDate AND (r.createdBy.userId = :userId OR :isAdmin = true) " +
           "GROUP BY r.category, FUNCTION('DATE_FORMAT', r.recordDate, '%Y-%m') " +
           "ORDER BY r.category, FUNCTION('DATE_FORMAT', r.recordDate, '%Y-%m') ASC")
    List<Object[]> getCategoryOverTime(@org.springframework.data.repository.query.Param("startDate") java.time.LocalDate startDate, @org.springframework.data.repository.query.Param("endDate") java.time.LocalDate endDate, @org.springframework.data.repository.query.Param("userId") Long userId, @org.springframework.data.repository.query.Param("isAdmin") boolean isAdmin);
    @Query("SELECT u.name, COUNT(r) " +
           "FROM User u LEFT JOIN FinancialRecord r ON r.createdBy = u AND r.isDeleted = false AND r.recordDate >= :startDate AND r.recordDate < :endDate AND (r.createdBy.userId = :userId OR :isAdmin = true) " +
           "GROUP BY u.name ORDER BY COUNT(r) DESC")
    List<Object[]> getUserActivity(@org.springframework.data.repository.query.Param("startDate") java.time.LocalDate startDate, @org.springframework.data.repository.query.Param("endDate") java.time.LocalDate endDate, @org.springframework.data.repository.query.Param("userId") Long userId, @org.springframework.data.repository.query.Param("isAdmin") boolean isAdmin);
}
