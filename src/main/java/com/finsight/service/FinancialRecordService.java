package com.finsight.service;

import com.finsight.dto.request.CreateRecordRequest;
import com.finsight.dto.request.UpdateRecordRequest;
import com.finsight.dto.response.RecordResponse;
import com.finsight.exception.ResourceNotFoundException;
import com.finsight.model.FinancialRecord;
import com.finsight.model.User;
import com.finsight.repository.FinancialRecordRepository;
import com.finsight.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.Slice;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.format.DateTimeFormatter;

@Service
@RequiredArgsConstructor
public class FinancialRecordService {
    private final FinancialRecordRepository recordRepository;
    private final UserRepository userRepository;
    private final AuditLogService auditLogService;
    private final BudgetService budgetService;

    @org.springframework.context.annotation.Lazy
    @org.springframework.beans.factory.annotation.Autowired
    private FinancialRecordService self;

    @PreAuthorize("hasRole('ADMIN')")
    public RecordResponse createRecord(CreateRecordRequest req, Long actorUserId) {
        // Idempotency: if the key already exists, return the existing record (200 OK at controller level)
        if (req.getIdempotencyKey() != null) {
            var existingOpt = recordRepository.findByCreatedBy_UserIdAndIdempotencyKey(actorUserId, req.getIdempotencyKey());
            if (existingOpt.isPresent()) {
                FinancialRecord existing = existingOpt.get();
                verifyIdempotencyPayloadMatch(existing, req);
                return toResponse(existing);
            }
        }

        try {
            return self.doCreateRecord(req, actorUserId);
        } catch (DataIntegrityViolationException e) {
            // Concurrent duplicate idempotency key — race condition between the check and the insert
            FinancialRecord existing = recordRepository.findByCreatedBy_UserIdAndIdempotencyKey(actorUserId, req.getIdempotencyKey())
                    .orElseThrow(() -> new RuntimeException("Record creation failed unexpectedly"));
            verifyIdempotencyPayloadMatch(existing, req);
            return toResponse(existing);
        }
    }

    @Transactional
    public RecordResponse doCreateRecord(CreateRecordRequest req, Long actorUserId) {
        User actor = userRepository.findById(actorUserId)
                .orElseThrow(() -> new ResourceNotFoundException("Actor user not found"));

        FinancialRecord record = new FinancialRecord();
        record.setCreatedBy(actor);
        record.setAmount(req.getAmount());
        record.setType(req.getType());
        record.setCategory(req.getCategory());
        record.setRecordDate(req.getRecordDate());
        record.setDescription(req.getDescription());
        record.setIdempotencyKey(req.getIdempotencyKey());

        record = recordRepository.saveAndFlush(record);

        // Audit log (REQUIRED — atomic with the business transaction, rolls back if this tx rolls back)
        auditLogService.record(actorUserId, "CREATE_RECORD", "RECORD", record.getRecordId(),
                "Created record: amount=" + record.getAmount() + " category=" + record.getCategory());

        // Budget check: only trigger for EXPENSE records
        if ("EXPENSE".equalsIgnoreCase(record.getType()) && record.getCategory() != null && record.getRecordDate() != null) {
            String monthYear = record.getRecordDate().format(DateTimeFormatter.ofPattern("yyyy-MM"));
            budgetService.checkBudgetExceededAfterRecord(record.getCategory(), monthYear, actorUserId);
        }

        return toResponse(record);
    }

    @Transactional(readOnly = true)
    @PreAuthorize("hasAnyRole('VIEWER', 'ANALYST', 'ADMIN')")
    public org.springframework.data.domain.Page<RecordResponse> getAllRecords(
            String type,
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
            java.util.List<String> allowedFields = java.util.Arrays.asList("amount", "type", "category", "recordDate", "createdAt", "updatedAt");
            for (org.springframework.data.domain.Sort.Order order : pageable.getSort()) {
                if (!allowedFields.contains(order.getProperty())) {
                    throw new com.finsight.exception.InvalidRequestException("Invalid sort field: " + order.getProperty());
                }
            }
        }

        org.springframework.data.jpa.domain.Specification<FinancialRecord> spec = (root, query, cb) -> {
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

            if (type != null) {
                predicates.add(cb.equal(root.get("type"), type));
            }
            if (category != null) {
                // User specifies exact string match for category
                predicates.add(cb.equal(root.get("category"), category));
            }
            if (startDate != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("recordDate"), startDate));
            }
            if (endDate != null) {
                predicates.add(cb.lessThanOrEqualTo(root.get("recordDate"), endDate));
            }

            return cb.and(predicates.toArray(new jakarta.persistence.criteria.Predicate[0]));
        };

        return recordRepository.findAll(spec, pageable).map(this::toResponse);
    }

    @Transactional
    @PreAuthorize("hasRole('ADMIN')")
    public RecordResponse updateRecord(Long id, UpdateRecordRequest req, Long actorUserId) {
        FinancialRecord record = recordRepository.findByIdAndIsDeletedFalse(id)
                .orElseThrow(() -> new ResourceNotFoundException("Financial record not found: " + id));

        // Optimistic lock check: the client sends back the version they read.
        // If the DB version doesn't match, we reject immediately before any mutation.
        // This is the correct approach — do NOT call record.setVersion(), that defeats @Version.
        if (!record.getVersion().equals(req.getVersion())) {
            throw new OptimisticLockingFailureException(
                    "Record " + id + " was modified by another user. Client has version=" + req.getVersion()
                    + " but current is version=" + record.getVersion() + ". Please refresh and retry.");
        }

        String oldDetails = "amount=" + record.getAmount() + " category=" + record.getCategory();

        record.setAmount(req.getAmount());
        record.setType(req.getType());
        record.setCategory(req.getCategory());
        record.setRecordDate(req.getRecordDate());
        record.setDescription(req.getDescription());
        // DO NOT call record.setVersion() — @Version is managed by Hibernate automatically

        record = recordRepository.save(record);

        String newDetails = "amount=" + record.getAmount() + " category=" + record.getCategory();
        auditLogService.record(actorUserId, "UPDATE_RECORD", "RECORD", record.getRecordId(),
                "Updated: " + oldDetails + " -> " + newDetails);

        return toResponse(record);
    }

    @Transactional
    @PreAuthorize("hasRole('ADMIN')")
    public void deleteRecord(Long id, Long actorUserId) {
        FinancialRecord record = recordRepository.findByIdAndIsDeletedFalse(id)
                .orElseThrow(() -> new ResourceNotFoundException("Financial record not found: " + id));
        record.setDeleted(true);
        recordRepository.save(record);
        auditLogService.record(actorUserId, "DELETE_RECORD", "RECORD", id, "Soft deleted record");
    }

    /**
     * Maps FinancialRecord entity to RecordResponse DTO.
     * NEVER return the entity directly — it contains the createdBy User (which has a password hash).
     */
    public RecordResponse toResponse(FinancialRecord r) {
        return RecordResponse.builder()
                .recordId(r.getRecordId())
                .createdByUserId(r.getCreatedBy() != null ? r.getCreatedBy().getUserId() : null)
                .createdByName(r.getCreatedBy() != null ? r.getCreatedBy().getName() : null)
                .amount(r.getAmount())
                .type(r.getType())
                .category(r.getCategory())
                .recordDate(r.getRecordDate())
                .description(r.getDescription())
                .isDeleted(r.isDeleted())
                .createdAt(r.getCreatedAt())
                .updatedAt(r.getUpdatedAt())
                .version(r.getVersion())
                .idempotencyKey(r.getIdempotencyKey())
                .build();
    }

    private void verifyIdempotencyPayloadMatch(FinancialRecord existing, CreateRecordRequest req) {
        boolean descMatch = (existing.getDescription() == null && req.getDescription() == null) ||
                (existing.getDescription() != null && existing.getDescription().equals(req.getDescription()));
        if (existing.getAmount().compareTo(req.getAmount()) != 0 ||
            !existing.getType().equals(req.getType()) ||
            !existing.getCategory().equals(req.getCategory()) ||
            !existing.getRecordDate().equals(req.getRecordDate()) ||
            !descMatch) {
            throw new IllegalArgumentException("Idempotency key already used with a different payload");
        }
    }

    @org.springframework.transaction.annotation.Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    public FinancialRecord saveRecordRequiresNew(FinancialRecord record) {
        return recordRepository.saveAndFlush(record);
    }
}
