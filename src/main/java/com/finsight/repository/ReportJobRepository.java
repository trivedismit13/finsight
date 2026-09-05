package com.finsight.repository;

import com.finsight.model.ReportJob;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface ReportJobRepository extends JpaRepository<ReportJob, Long> {
    List<ReportJob> findByStatus(String status);

    @Modifying
    @Query("UPDATE ReportJob r SET r.status = 'PROCESSING' WHERE r.jobId = :jobId AND r.status = 'PENDING'")
    int claimJob(@Param("jobId") Long jobId);

    @Modifying
    @Query("UPDATE ReportJob r SET r.status = :status, r.completedAt = :completedAt, r.filePath = :filePath, r.failureReason = :failureReason WHERE r.jobId = :jobId AND r.status = 'PROCESSING'")
    int updateJobState(@Param("jobId") Long jobId, @Param("status") String status, @Param("completedAt") LocalDateTime completedAt, @Param("filePath") String filePath, @Param("failureReason") String failureReason);
}
