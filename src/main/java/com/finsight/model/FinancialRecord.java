package com.finsight.model;

import jakarta.persistence.*;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "financial_records", 
    indexes = {
        @Index(name = "idx_fr_dashboard", columnList = "is_deleted, record_date, category"),
        @Index(name = "idx_fr_user_reports", columnList = "created_by, is_deleted, record_date"),
        @Index(name = "idx_fr_budget_filter", columnList = "category, type, is_deleted, record_date")
    },
    uniqueConstraints = {
        @UniqueConstraint(name = "unique_user_idempotency", columnNames = {"created_by", "idempotency_key"})
    }
)
public class FinancialRecord {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "record_id")
    private Long recordId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by", nullable = false)
    private User createdBy;

    @Column(nullable = false, precision = 15, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false, length = 50)
    private String type; // INCOME, EXPENSE

    @Column(nullable = false, length = 100)
    private String category;
    
    @Column(name = "record_date", nullable = false)
    private LocalDate recordDate;

    @Column(columnDefinition = "TEXT")
    private String description;
    
    @Column(name = "is_deleted", nullable = false)
    private boolean isDeleted = false;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Version
    @Column(nullable = false)
    private Long version = 0L;

    @Column(name = "idempotency_key")
    private String idempotencyKey;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
