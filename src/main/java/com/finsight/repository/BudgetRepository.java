package com.finsight.repository;

import com.finsight.model.Budget;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.Optional;

@Repository
public interface BudgetRepository extends JpaRepository<Budget, Long> {
    Optional<Budget> findByCategoryAndMonthYear(com.finsight.model.ExpenseCategory category, String monthYear);



    @Modifying
    @Query("UPDATE Budget c SET c.isAlertSent = true WHERE c.budgetId = :id AND c.isAlertSent = false")
    int markAlertSentIfFalse(@Param("id") Long id);
}
