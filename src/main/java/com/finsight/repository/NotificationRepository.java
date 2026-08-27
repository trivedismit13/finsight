package com.finsight.repository;

import com.finsight.model.Notification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface NotificationRepository extends JpaRepository<Notification, Long> {

    @Modifying
    @Query("UPDATE Notification n SET n.status = 'PROCESSING', n.lastAttemptAt = :now WHERE n.notificationId = :id AND n.status IN ('PENDING', 'FAILED')")
    int claimNotification(@Param("id") Long id, @Param("now") LocalDateTime now);

    @Query("SELECT n FROM Notification n WHERE n.status = 'PENDING' OR (n.status = 'FAILED' AND n.nextAttemptAt <= :now)")
    List<Notification> findPendingAndReadyFailed(@Param("now") LocalDateTime now, org.springframework.data.domain.Pageable pageable);

    long countByStatus(String status);

    @Modifying
    @Query("UPDATE Notification n SET n.status = 'SENT', n.deliveredAt = :deliveredAt WHERE n.notificationId = :id AND n.status = 'PROCESSING'")
    int markSuccess(@Param("id") Long id, @Param("deliveredAt") LocalDateTime deliveredAt);

    @Modifying
    @Query("UPDATE Notification n SET n.status = 'FAILED', n.retryCount = :retryCount, n.nextAttemptAt = :nextAttemptAt WHERE n.notificationId = :id AND n.status = 'PROCESSING'")
    int markFailure(@Param("id") Long id, @Param("retryCount") int retryCount, @Param("nextAttemptAt") LocalDateTime nextAttemptAt);

    @Modifying
    @Query("UPDATE Notification n SET n.status = 'DEAD_LETTER', n.deadLetterReason = :reason WHERE n.notificationId = :id AND n.status = 'PROCESSING'")
    int markDeadLetter(@Param("id") Long id, @Param("reason") String reason);

    @Query("SELECT n FROM Notification n WHERE n.status = 'PROCESSING' AND n.lastAttemptAt <= :timeout")
    List<Notification> findStaleProcessing(@Param("timeout") LocalDateTime timeout, org.springframework.data.domain.Pageable pageable);

    @org.springframework.data.jpa.repository.EntityGraph(attributePaths = "userId")
    List<Notification> findByStatus(String status);
}
