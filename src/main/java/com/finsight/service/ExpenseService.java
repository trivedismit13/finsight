package com.finsight.service;

import com.finsight.dto.request.CreateExpenseRequest;
import com.finsight.dto.request.UpdateExpenseRequest;
import com.finsight.dto.response.ExpenseResponse;
import com.finsight.exception.ResourceNotFoundException;
import com.finsight.model.Expense;
import com.finsight.model.ExpenseCategory;
import com.finsight.model.ExpenseStatus;
import com.finsight.model.Role;
import com.finsight.model.User;
import com.finsight.repository.ExpenseRepository;
import com.finsight.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import java.time.LocalDateTime;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.Slice;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.annotation.Transactional;

import java.time.format.DateTimeFormatter;

@Service
@RequiredArgsConstructor
public class ExpenseService {
    private final ExpenseRepository expenseRepository;
    private final UserRepository userRepository;
    private final AuditLogService auditLogService;
    private final BudgetService budgetService;
    private final NotificationDispatcherService notificationDispatcherService;

    @org.springframework.context.annotation.Lazy
    @org.springframework.beans.factory.annotation.Autowired
    private ExpenseService self;

    public ExpenseResponse createExpense(CreateExpenseRequest req, String idempotencyKey, Long actorUserId) {
        // Idempotency: if the key already exists, return the existing expense (200 OK at controller level)
        if (idempotencyKey != null) {
            var existingOpt = expenseRepository.findByCreatedBy_UserIdAndIdempotencyKey(actorUserId, idempotencyKey);
            if (existingOpt.isPresent()) {
                Expense existing = existingOpt.get();
                verifyIdempotencyPayloadMatch(existing, req);
                return toResponse(existing);
            }
        }

        try {
            return self.doCreateExpense(req, idempotencyKey, actorUserId);
        } catch (DataIntegrityViolationException e) {
            // Concurrent duplicate idempotency key — race condition between the check and the insert
            Expense existing = expenseRepository.findByCreatedBy_UserIdAndIdempotencyKey(actorUserId, idempotencyKey)
                    .orElseThrow(() -> new RuntimeException("Expense creation failed unexpectedly"));
            verifyIdempotencyPayloadMatch(existing, req);
            return toResponse(existing);
        }
    }

    @Transactional
    public ExpenseResponse doCreateExpense(CreateExpenseRequest req, String idempotencyKey, Long actorUserId) {
        User actor = userRepository.findById(actorUserId)
                .orElseThrow(() -> new ResourceNotFoundException("Actor user not found"));

        Expense expense = new Expense();
        expense.setCreatedBy(actor);
        expense.setAmount(req.getAmount());
        expense.setCategory(req.getCategory());
        expense.setExpenseDate(req.getExpenseDate());
        expense.setDescription(req.getDescription());
        expense.setIdempotencyKey(idempotencyKey);
        expense.setStatus(ExpenseStatus.DRAFT);

        expense = expenseRepository.saveAndFlush(expense);

        // Audit log (REQUIRED — atomic with the business transaction, rolls back if this tx rolls back)
        auditLogService.record(actorUserId, "CREATE_EXPENSE", "EXPENSE", expense.getExpenseId(),
                "Created expense: " + expense.getExpenseId());


        return toResponse(expense);
    }

    @Transactional(readOnly = true)
    @PreAuthorize("hasAnyRole('EMPLOYEE', 'MANAGER', 'FINANCE_ADMIN')")
    public org.springframework.data.domain.Page<ExpenseResponse> getAllExpenses(
            String status,
            String category,
            java.time.LocalDate startDate,
            java.time.LocalDate endDate,
            org.springframework.data.domain.Pageable pageable,
            Long actorId) {

        User actor = userRepository.findById(actorId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));

        if (startDate != null && endDate != null && startDate.isAfter(endDate)) {
            throw new com.finsight.exception.InvalidRequestException("startDate cannot be after endDate");
        }

        // Validate allowed sort fields
        if (pageable.getSort().isSorted()) {
            java.util.List<String> allowedFields = java.util.Arrays.asList("amount", "category", "expenseDate", "createdAt", "updatedAt");
            for (org.springframework.data.domain.Sort.Order order : pageable.getSort()) {
                if (!allowedFields.contains(order.getProperty())) {
                    throw new com.finsight.exception.InvalidRequestException("Invalid sort field: " + order.getProperty());
                }
            }
        }

        org.springframework.data.jpa.domain.Specification<Expense> spec = (root, query, cb) -> {
            java.util.List<jakarta.persistence.criteria.Predicate> predicates = new java.util.ArrayList<>();

            // Mandatory soft-delete invariant
            predicates.add(cb.isFalse(root.get("isDeleted")));

            // Mandatory cross-user ownership boundary
            if (actor.getRole() == Role.FINANCE_ADMIN) {
                // COMPANY scope: Can see all records
            } else if (actor.getRole() == Role.MANAGER) {
                // TEAM scope: Can see own records AND records of users they manage (excluding DRAFT)
                predicates.add(cb.or(
                        cb.equal(root.get("createdBy").get("userId"), actorId),
                        cb.and(
                            cb.equal(root.get("createdBy").get("manager").get("userId"), actorId),
                            cb.notEqual(root.get("status"), ExpenseStatus.DRAFT)
                        )
                ));
            } else {
                // EMPLOYEE scope: Can see only own records
                predicates.add(cb.equal(root.get("createdBy").get("userId"), actorId));
            }

            if (query.getResultType() != Long.class && query.getResultType() != long.class) {
                root.fetch("createdBy", jakarta.persistence.criteria.JoinType.LEFT);
            }

            if (status != null) {
                predicates.add(cb.equal(root.get("status"), ExpenseStatus.valueOf(status)));
            }
            if (category != null) {
                // User specifies exact string match for category
                predicates.add(cb.equal(root.get("category"), ExpenseCategory.valueOf(category)));
            }
            if (startDate != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("expenseDate"), startDate));
            }
            if (endDate != null) {
                predicates.add(cb.lessThanOrEqualTo(root.get("expenseDate"), endDate));
            }

            return cb.and(predicates.toArray(new jakarta.persistence.criteria.Predicate[0]));
        };

        return expenseRepository.findAll(spec, pageable).map(this::toResponse);
    }

    @Transactional
    public ExpenseResponse updateExpense(Long id, UpdateExpenseRequest req, Long actorUserId) {
        Expense expense = expenseRepository.findByIdAndIsDeletedFalse(id)
                .orElseThrow(() -> new ResourceNotFoundException("Expense not found: " + id));

        if (!expense.getCreatedBy().getUserId().equals(actorUserId)) {
            throw new AccessDeniedException("Only the owner can update this expense.");
        }

        if (expense.getStatus() != ExpenseStatus.DRAFT && expense.getStatus() != ExpenseStatus.REJECTED) {
            throw new IllegalStateException("Only DRAFT or REJECTED expenses can be updated.");
        }

        if (!expense.getVersion().equals(req.getVersion())) {
            throw new org.springframework.dao.OptimisticLockingFailureException("Expense " + id + " was modified by another user. Client has version=" + req.getVersion() + " but current is version=" + expense.getVersion() + ". Please refresh and retry.");
        }

        // Snapshot state for audit
        String oldState = expense.getAmount() + "|" + expense.getCategory();

        expense.setAmount(req.getAmount());
        expense.setCategory(req.getCategory());
        expense.setExpenseDate(req.getExpenseDate());
        expense.setDescription(req.getDescription());

        expense = expenseRepository.save(expense);

        auditLogService.record(actorUserId, "UPDATE_EXPENSE", "EXPENSE", expense.getExpenseId(),
                "Updated expense from [" + oldState + "] to [" + expense.getAmount() + "|" + expense.getCategory() + "]");

        return toResponse(expense);
    }

    @Transactional
    public void deleteExpense(Long id, Long actorUserId) {
        Expense expense = expenseRepository.findByIdAndIsDeletedFalse(id)
                .orElseThrow(() -> new ResourceNotFoundException("Expense not found: " + id));

        if (!expense.getCreatedBy().getUserId().equals(actorUserId)) {
            throw new AccessDeniedException("Only the owner can delete this expense.");
        }

        if (expense.getStatus() != ExpenseStatus.DRAFT) {
            throw new IllegalStateException("Only DRAFT expenses can be deleted.");
        }

        expense.setDeleted(true);
        expenseRepository.save(expense);

        auditLogService.record(actorUserId, "DELETE_EXPENSE", "EXPENSE", expense.getExpenseId(),
                "Deleted expense: " + expense.getExpenseId());
    }

    @Transactional
    public ExpenseResponse submitExpense(Long id, Long actorUserId) {
        Expense expense = expenseRepository.findByIdAndIsDeletedFalse(id)
                .orElseThrow(() -> new ResourceNotFoundException("Expense not found: " + id));

        if (!expense.getCreatedBy().getUserId().equals(actorUserId)) {
            throw new AccessDeniedException("Only the owner can submit this expense.");
        }

        if (expense.getStatus() != ExpenseStatus.DRAFT && expense.getStatus() != ExpenseStatus.REJECTED) {
            throw new IllegalStateException("Only DRAFT or REJECTED expenses can be submitted.");
        }

        expense.setStatus(ExpenseStatus.PENDING_APPROVAL);
        expense.setSubmittedAt(LocalDateTime.now());
        expense = expenseRepository.save(expense);

        auditLogService.record(actorUserId, "SUBMIT_EXPENSE", "EXPENSE", expense.getExpenseId(),
                "Submitted expense: " + expense.getExpenseId());

        return toResponse(expense);
    }

    /**
     * Maps Expense entity to ExpenseResponse DTO.
     * NEVER return the entity directly — it contains the createdBy User (which has a password hash).
     */
    public ExpenseResponse toResponse(Expense r) {
        return ExpenseResponse.builder()
                .expenseId(r.getExpenseId())
                .createdByUserId(r.getCreatedBy() != null ? r.getCreatedBy().getUserId() : null)
                .createdByName(r.getCreatedBy() != null ? r.getCreatedBy().getName() : null)
                .amount(r.getAmount())
                .currency(r.getCurrency())
                .status(r.getStatus().name())
                .category(r.getCategory().name())
                .expenseDate(r.getExpenseDate())
                .description(r.getDescription())
                .isDeleted(r.isDeleted())
                .createdAt(r.getCreatedAt())
                .updatedAt(r.getUpdatedAt())
                .version(r.getVersion())
                .idempotencyKey(r.getIdempotencyKey())
                .submittedAt(r.getSubmittedAt())
                .approvedAt(r.getApprovedAt())
                .approvedByUserId(r.getApprovedBy() != null ? r.getApprovedBy().getUserId() : null)
                .rejectedAt(r.getRejectedAt())
                .rejectedByUserId(r.getRejectedBy() != null ? r.getRejectedBy().getUserId() : null)
                .rejectionReason(r.getRejectionReason())
                .build();
    }

    private void verifyIdempotencyPayloadMatch(Expense existing, CreateExpenseRequest req) {
        boolean descMatch = (existing.getDescription() == null && req.getDescription() == null) ||
                (existing.getDescription() != null && existing.getDescription().equals(req.getDescription()));
        if (existing.getAmount().compareTo(req.getAmount()) != 0) {
            throw new IllegalArgumentException("Idempotency key already used with a different amount: " + existing.getAmount() + " vs " + req.getAmount());
        }
        if (!existing.getCategory().equals(req.getCategory())) {
            throw new IllegalArgumentException("Idempotency key already used with a different category: " + existing.getCategory() + " vs " + req.getCategory());
        }
        if (!existing.getExpenseDate().equals(req.getExpenseDate())) {
            throw new IllegalArgumentException("Idempotency key already used with a different date: " + existing.getExpenseDate() + " vs " + req.getExpenseDate());
        }
        if (!descMatch) {
            throw new IllegalArgumentException("Idempotency key already used with a different description");
        }
    }

    @org.springframework.transaction.annotation.Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    public Expense saveExpenseRequiresNew(Expense expense) {
        return expenseRepository.saveAndFlush(expense);
    }

    public org.springframework.data.domain.Slice<ExpenseResponse> getTeamExpenses(Long managerId, org.springframework.data.domain.Pageable pageable) {
        return expenseRepository.findTeamExpenses(managerId, pageable).map(this::toResponse);
    }

    @Transactional
    public ExpenseResponse approveExpense(Long id, Long managerId) {
        Expense expense = expenseRepository.findByIdAndIsDeletedFalse(id)
                .orElseThrow(() -> new ResourceNotFoundException("Expense not found: " + id));

        validateManagerAccess(expense, managerId);

        if (expense.getStatus() != ExpenseStatus.PENDING_APPROVAL) {
            throw new IllegalStateException("Only PENDING_APPROVAL expenses can be approved.");
        }

        User manager = userRepository.findById(managerId)
                .orElseThrow(() -> new ResourceNotFoundException("Manager not found"));

        expense.setStatus(ExpenseStatus.APPROVED);
        expense.setApprovedAt(LocalDateTime.now());
        expense.setApprovedBy(manager);
        expense = expenseRepository.save(expense);

        auditLogService.record(managerId, "APPROVE_EXPENSE", "EXPENSE", expense.getExpenseId(),
                "Approved expense: " + expense.getExpenseId());

        notificationDispatcherService.enqueueNotification(
                expense.getCreatedBy().getUserId(),
                "EXPENSE_APPROVED",
                "Your expense " + id + " has been approved."
        );

        if (expense.getCategory() != null && expense.getExpenseDate() != null) {
            String monthYear = expense.getExpenseDate().format(DateTimeFormatter.ofPattern("yyyy-MM"));
            budgetService.checkBudgetExceededAfterRecord(expense.getCategory(), monthYear, managerId);
        }

        return toResponse(expense);
    }

    @Transactional
    public ExpenseResponse rejectExpense(Long id, Long managerId, String reason) {
        Expense expense = expenseRepository.findByIdAndIsDeletedFalse(id)
                .orElseThrow(() -> new ResourceNotFoundException("Expense not found: " + id));

        validateManagerAccess(expense, managerId);

        if (expense.getStatus() != ExpenseStatus.PENDING_APPROVAL) {
            throw new IllegalStateException("Only PENDING_APPROVAL expenses can be rejected.");
        }

        User manager = userRepository.findById(managerId)
                .orElseThrow(() -> new ResourceNotFoundException("Manager not found"));

        expense.setStatus(ExpenseStatus.REJECTED);
        expense.setRejectedAt(LocalDateTime.now());
        expense.setRejectedBy(manager);
        expense.setRejectionReason(reason);
        expense = expenseRepository.save(expense);

        auditLogService.record(managerId, "REJECT_EXPENSE", "EXPENSE", expense.getExpenseId(),
                "Rejected expense: " + expense.getExpenseId());

        notificationDispatcherService.enqueueNotification(
                expense.getCreatedBy().getUserId(),
                "EXPENSE_REJECTED",
                "Your expense " + id + " has been rejected. Reason: " + reason
        );

        return toResponse(expense);
    }

    @Transactional
    @PreAuthorize("hasRole('FINANCE_ADMIN')")
    public ExpenseResponse processExpense(Long id, Long adminId) {
        Expense expense = expenseRepository.findByIdAndIsDeletedFalse(id)
                .orElseThrow(() -> new ResourceNotFoundException("Expense not found: " + id));

        if (expense.getStatus() != ExpenseStatus.APPROVED) {
            throw new IllegalStateException("Only APPROVED expenses can be processed.");
        }

        expense.setStatus(ExpenseStatus.PROCESSED);
        expense = expenseRepository.save(expense);

        auditLogService.record(adminId, "PROCESS_EXPENSE", "EXPENSE", expense.getExpenseId(),
                "Processed expense");

        notificationDispatcherService.enqueueNotification(
                expense.getCreatedBy().getUserId(),
                "EXPENSE_PROCESSED",
                "Your expense " + id + " has been processed."
        );

        return toResponse(expense);
    }

    private void validateManagerAccess(Expense expense, Long managerId) {
        User creator = expense.getCreatedBy();
        if (creator.getManager() == null || !creator.getManager().getUserId().equals(managerId)) {
            throw new AccessDeniedException("You are not the manager of the user who submitted this expense.");
        }
    }
}
