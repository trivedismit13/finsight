package com.finsight.service;

import com.finsight.dto.request.CreateExpenseRequest;
import com.finsight.dto.request.UpdateExpenseRequest;
import com.finsight.dto.response.ExpenseResponse;
import com.finsight.exception.ResourceNotFoundException;
import com.finsight.model.Expense;
import com.finsight.model.ExpenseCategory;
import com.finsight.model.ExpenseStatus;
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
    private final ExpenseRepository recordRepository;
    private final UserRepository userRepository;
    private final AuditLogService auditLogService;
    private final BudgetService budgetService;
    private final NotificationDispatcherService notificationDispatcherService;

    @org.springframework.context.annotation.Lazy
    @org.springframework.beans.factory.annotation.Autowired
    private ExpenseService self;

    public ExpenseResponse createRecord(CreateExpenseRequest req, Long actorUserId) {
        // Idempotency: if the key already exists, return the existing record (200 OK at controller level)
        if (req.getIdempotencyKey() != null) {
            var existingOpt = recordRepository.findByCreatedBy_UserIdAndIdempotencyKey(actorUserId, req.getIdempotencyKey());
            if (existingOpt.isPresent()) {
                Expense existing = existingOpt.get();
                verifyIdempotencyPayloadMatch(existing, req);
                return toResponse(existing);
            }
        }

        try {
            return self.doCreateRecord(req, actorUserId);
        } catch (DataIntegrityViolationException e) {
            // Concurrent duplicate idempotency key — race condition between the check and the insert
            Expense existing = recordRepository.findByCreatedBy_UserIdAndIdempotencyKey(actorUserId, req.getIdempotencyKey())
                    .orElseThrow(() -> new RuntimeException("Record creation failed unexpectedly"));
            verifyIdempotencyPayloadMatch(existing, req);
            return toResponse(existing);
        }
    }

    @Transactional
    public ExpenseResponse doCreateRecord(CreateExpenseRequest req, Long actorUserId) {
        User actor = userRepository.findById(actorUserId)
                .orElseThrow(() -> new ResourceNotFoundException("Actor user not found"));

        Expense record = new Expense();
        record.setCreatedBy(actor);
        record.setAmount(req.getAmount());
        record.setCategory(ExpenseCategory.valueOf(req.getCategory()));
        record.setExpenseDate(req.getExpenseDate());
        record.setDescription(req.getDescription());
        record.setIdempotencyKey(req.getIdempotencyKey());
        record.setStatus(ExpenseStatus.DRAFT);

        record = recordRepository.saveAndFlush(record);

        // Audit log (REQUIRED — atomic with the business transaction, rolls back if this tx rolls back)
        auditLogService.record(actorUserId, "CREATE_RECORD", "RECORD", record.getExpenseId(),
                "Created record: amount=" + record.getAmount() + " category=" + record.getCategory());

        // Budget check: always trigger for expenses
        if (record.getCategory() != null && record.getExpenseDate() != null) {
            String monthYear = record.getExpenseDate().format(DateTimeFormatter.ofPattern("yyyy-MM"));
            budgetService.checkBudgetExceededAfterRecord(record.getCategory().name(), monthYear, actorUserId);
        }

        return toResponse(record);
    }

    @Transactional(readOnly = true)
    @PreAuthorize("hasAnyRole('VIEWER', 'ANALYST', 'ADMIN')")
    public org.springframework.data.domain.Page<ExpenseResponse> getAllRecords(
            String status,
            String category,
            java.time.LocalDate startDate,
            java.time.LocalDate endDate,
            org.springframework.data.domain.Pageable pageable,
            Long actorId,
            boolean isAdmin) {

        if (startDate != null && endDate != null && startDate.isAfter(endDate)) {
            throw new com.finsight.exception.InvalidRequestException("startDate cannot be after endDate");
        }

        // Validate allowed sort fields
        if (pageable.getSort().isSorted()) {
            java.util.List<String> allowedFields = java.util.Arrays.asList("amount", "type", "category", "expenseDate", "createdAt", "updatedAt");
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
            if (!isAdmin) {
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

        return recordRepository.findAll(spec, pageable).map(this::toResponse);
    }

    @Transactional
    public ExpenseResponse updateRecord(Long id, UpdateExpenseRequest req, Long actorUserId) {
        Expense record = recordRepository.findByIdAndIsDeletedFalse(id)
                .orElseThrow(() -> new ResourceNotFoundException("Expense not found: " + id));

        if (!record.getCreatedBy().getUserId().equals(actorUserId)) {
            throw new AccessDeniedException("Only the owner can update this expense.");
        }

        if (record.getStatus() != ExpenseStatus.DRAFT) {
            throw new IllegalStateException("Only DRAFT expenses can be updated.");
        }

        if (!record.getVersion().equals(req.getVersion())) {
            throw new org.springframework.dao.OptimisticLockingFailureException("Record " + id + " was modified by another user. Client has version=" + req.getVersion() + " but current is version=" + record.getVersion() + ". Please refresh and retry.");
        }

        // Snapshot state for audit
        String oldState = record.getAmount() + "|" + record.getCategory();

        record.setAmount(req.getAmount());
        record.setCategory(ExpenseCategory.valueOf(req.getCategory()));
        record.setExpenseDate(req.getExpenseDate());
        record.setDescription(req.getDescription());

        record = recordRepository.save(record);

        auditLogService.record(actorUserId, "UPDATE_RECORD", "RECORD", record.getExpenseId(),
                "Updated record from [" + oldState + "] to [" + record.getAmount() + "|" + record.getCategory() + "]");

        return toResponse(record);
    }

    @Transactional
    public void deleteRecord(Long id, Long actorUserId) {
        Expense record = recordRepository.findByIdAndIsDeletedFalse(id)
                .orElseThrow(() -> new ResourceNotFoundException("Expense not found: " + id));

        if (!record.getCreatedBy().getUserId().equals(actorUserId)) {
            throw new AccessDeniedException("Only the owner can delete this expense.");
        }

        if (record.getStatus() != ExpenseStatus.DRAFT) {
            throw new IllegalStateException("Only DRAFT expenses can be deleted.");
        }

        record.setDeleted(true);
        recordRepository.save(record);

        auditLogService.record(actorUserId, "DELETE_RECORD", "RECORD", record.getExpenseId(),
                "Soft deleted record: " + id);
    }

    @Transactional
    public ExpenseResponse submitExpense(Long id, Long actorUserId) {
        Expense record = recordRepository.findByIdAndIsDeletedFalse(id)
                .orElseThrow(() -> new ResourceNotFoundException("Expense not found: " + id));

        if (!record.getCreatedBy().getUserId().equals(actorUserId)) {
            throw new AccessDeniedException("Only the owner can submit this expense.");
        }

        if (record.getStatus() != ExpenseStatus.DRAFT) {
            throw new IllegalStateException("Only DRAFT expenses can be submitted.");
        }

        record.setStatus(ExpenseStatus.PENDING_APPROVAL);
        record.setSubmittedAt(LocalDateTime.now());
        record = recordRepository.save(record);

        auditLogService.record(actorUserId, "SUBMIT_EXPENSE", "RECORD", record.getExpenseId(),
                "Submitted expense for approval");

        return toResponse(record);
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
                .rejectionReason(r.getRejectionReason())
                .build();
    }

    private void verifyIdempotencyPayloadMatch(Expense existing, CreateExpenseRequest req) {
        boolean descMatch = (existing.getDescription() == null && req.getDescription() == null) ||
                (existing.getDescription() != null && existing.getDescription().equals(req.getDescription()));
        if (existing.getAmount().compareTo(req.getAmount()) != 0) {
            throw new IllegalArgumentException("Idempotency key already used with a different amount: " + existing.getAmount() + " vs " + req.getAmount());
        }
        if (!existing.getCategory().name().equals(req.getCategory())) {
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
    public Expense saveRecordRequiresNew(Expense record) {
        return recordRepository.saveAndFlush(record);
    }

    public org.springframework.data.domain.Slice<ExpenseResponse> getTeamExpenses(Long managerId, org.springframework.data.domain.Pageable pageable) {
        return recordRepository.findTeamExpenses(managerId, pageable).map(this::toResponse);
    }

    @Transactional
    public ExpenseResponse approveExpense(Long id, Long managerId) {
        Expense record = recordRepository.findByIdAndIsDeletedFalse(id)
                .orElseThrow(() -> new ResourceNotFoundException("Expense not found: " + id));

        validateManagerAccess(record, managerId);

        if (record.getStatus() != ExpenseStatus.PENDING_APPROVAL) {
            throw new IllegalStateException("Only PENDING_APPROVAL expenses can be approved.");
        }

        User manager = userRepository.findById(managerId)
                .orElseThrow(() -> new ResourceNotFoundException("Manager not found"));

        record.setStatus(ExpenseStatus.APPROVED);
        record.setApprovedAt(LocalDateTime.now());
        record.setApprovedBy(manager);
        record = recordRepository.save(record);

        auditLogService.record(managerId, "APPROVE_EXPENSE", "RECORD", record.getExpenseId(),
                "Approved expense");

        notificationDispatcherService.enqueueNotification(
                record.getCreatedBy().getUserId(),
                "EXPENSE_APPROVED",
                "Your expense " + id + " has been approved."
        );

        return toResponse(record);
    }

    @Transactional
    public ExpenseResponse rejectExpense(Long id, Long managerId, String reason) {
        Expense record = recordRepository.findByIdAndIsDeletedFalse(id)
                .orElseThrow(() -> new ResourceNotFoundException("Expense not found: " + id));

        validateManagerAccess(record, managerId);

        if (record.getStatus() != ExpenseStatus.PENDING_APPROVAL) {
            throw new IllegalStateException("Only PENDING_APPROVAL expenses can be rejected.");
        }

        record.setStatus(ExpenseStatus.REJECTED);
        record.setRejectedAt(LocalDateTime.now());
        record.setRejectionReason(reason);
        record = recordRepository.save(record);

        auditLogService.record(managerId, "REJECT_EXPENSE", "RECORD", record.getExpenseId(),
                "Rejected expense: " + reason);

        notificationDispatcherService.enqueueNotification(
                record.getCreatedBy().getUserId(),
                "EXPENSE_REJECTED",
                "Your expense " + id + " has been rejected. Reason: " + reason
        );

        return toResponse(record);
    }

    @Transactional
    @PreAuthorize("hasRole('FINANCE_ADMIN') or hasRole('ADMIN')")
    public ExpenseResponse processExpense(Long id, Long adminId) {
        Expense record = recordRepository.findByIdAndIsDeletedFalse(id)
                .orElseThrow(() -> new ResourceNotFoundException("Expense not found: " + id));

        if (record.getStatus() != ExpenseStatus.APPROVED) {
            throw new IllegalStateException("Only APPROVED expenses can be processed.");
        }

        record.setStatus(ExpenseStatus.PROCESSED);
        record = recordRepository.save(record);

        auditLogService.record(adminId, "PROCESS_EXPENSE", "RECORD", record.getExpenseId(),
                "Processed expense");

        notificationDispatcherService.enqueueNotification(
                record.getCreatedBy().getUserId(),
                "EXPENSE_PROCESSED",
                "Your expense " + id + " has been processed and reimbursed."
        );

        return toResponse(record);
    }

    private void validateManagerAccess(Expense record, Long managerId) {
        User creator = record.getCreatedBy();
        if (creator.getManager() == null || !creator.getManager().getUserId().equals(managerId)) {
            throw new AccessDeniedException("You are not the manager of the user who submitted this expense.");
        }
    }
}
