package com.finsight.service;

import com.finsight.model.AuditLog;
import com.finsight.model.User;
import com.finsight.repository.AuditLogRepository;
import com.finsight.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class AuditLogService {
    private final AuditLogRepository auditLogRepository;
    private final UserRepository userRepository;

    @Transactional(propagation = Propagation.REQUIRED)
    public void record(Long actorUserId, String action, String entityType, Long entityId, String details) {
        User actor = userRepository.findById(actorUserId).orElse(null);
        if (actor == null) return;

        AuditLog log = new AuditLog();
        log.setActorUserId(actor);
        log.setAction(action);
        log.setEntityType(entityType);
        log.setEntityId(entityId);
        log.setDetails(details);
        auditLogRepository.save(log);
    }
    @Transactional(readOnly = true)
    @PreAuthorize("hasRole('FINANCE_ADMIN')")
    public org.springframework.data.domain.Page<com.finsight.dto.response.AuditLogResponse> getAllAuditLogs(org.springframework.data.domain.Pageable pageable) {
        return auditLogRepository.findAll(pageable).map(log -> com.finsight.dto.response.AuditLogResponse.builder()
                .auditId(log.getAuditId())
                .actorUserId(log.getActorUserId() != null ? log.getActorUserId().getUserId() : null)
                .actorName(log.getActorUserId() != null ? log.getActorUserId().getName() : null)
                .action(log.getAction())
                .entityType(log.getEntityType())
                .entityId(log.getEntityId())
                .details(log.getDetails())
                .createdAt(log.getCreatedAt())
                .build());
    }
}
