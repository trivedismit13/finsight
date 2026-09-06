package com.finsight.model;

import jakarta.persistence.*;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "category_budgets", uniqueConstraints = {
    @UniqueConstraint(columnNames = {"category", "month_year"})
})
public class Budget {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "budget_id")
    private Long budgetId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 100)
    private ExpenseCategory category;

    @Column(name = "month_year", nullable = false, length = 7)
    private String monthYear; // e.g., 2026-08

    @Column(name = "budget_amount", nullable = false, precision = 15, scale = 2)
    private BigDecimal budgetAmount;

    @Column(name = "is_alert_sent", nullable = false)
    private boolean isAlertSent = false;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by", nullable = false)
    private User createdBy;

    @Column(name = "created_at")
    private LocalDateTime createdAt;
    
    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
