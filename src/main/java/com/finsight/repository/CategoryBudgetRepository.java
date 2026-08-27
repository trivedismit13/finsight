package com.finsight.repository;

import com.finsight.model.CategoryBudget;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.Optional;

@Repository
public interface CategoryBudgetRepository extends JpaRepository<CategoryBudget, Long> {
    Optional<CategoryBudget> findByCategoryAndMonthYear(String category, String monthYear);

    @Query("SELECT c FROM CategoryBudget c WHERE c.createdBy.userId = :userId OR :isAdmin = true")
    java.util.List<CategoryBudget> findByUser(@Param("userId") Long userId, @Param("isAdmin") boolean isAdmin, org.springframework.data.domain.Sort sort);

    @Modifying
    @Query("UPDATE CategoryBudget c SET c.isAlertSent = true WHERE c.budgetId = :id AND c.isAlertSent = false")
    int markAlertSentIfFalse(@Param("id") Long id);
}
