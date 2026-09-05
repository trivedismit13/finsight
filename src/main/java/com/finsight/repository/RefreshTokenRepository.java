package com.finsight.repository;

import com.finsight.model.RefreshToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {
    Optional<RefreshToken> findByTokenHash(String tokenHash);
    List<RefreshToken> findAllByUser_UserIdAndRevokedFalse(Long userId);
    
    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.data.jpa.repository.Query("UPDATE RefreshToken r SET r.revoked = true, r.revokedAt = :now WHERE r.tokenId = :tokenId AND r.revoked = false AND r.expiresAt > :now")
    int consumeTokenAtomically(@org.springframework.data.repository.query.Param("tokenId") Long tokenId, @org.springframework.data.repository.query.Param("now") java.time.LocalDateTime now);
}
