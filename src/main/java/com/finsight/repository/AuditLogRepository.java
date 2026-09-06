package com.finsight.repository;

import com.finsight.model.AuditLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface AuditLogRepository extends JpaRepository<AuditLog, Long> {
    @org.springframework.data.jpa.repository.EntityGraph(attributePaths = "actorUserId")
    java.util.List<AuditLog> findAll();

    @org.springframework.data.jpa.repository.EntityGraph(attributePaths = "actorUserId")
    org.springframework.data.domain.Page<AuditLog> findAll(org.springframework.data.domain.Pageable pageable);
}
