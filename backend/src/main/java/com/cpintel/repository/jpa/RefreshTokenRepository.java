package com.cpintel.repository.jpa;

import com.cpintel.entity.RefreshToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;

@Repository
public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {
    Optional<RefreshToken> findByToken(String token);

    @Modifying
    @Query("DELETE FROM RefreshToken rt WHERE rt.user.userId = :userId")
    void deleteAllByUserId(@Param("userId") Long userId);

    @Modifying
    @Query("DELETE FROM RefreshToken rt WHERE rt.expiresAt < :now")
    void deleteExpiredTokens(@Param("now") Instant now);

    @Modifying
    @Query("UPDATE RefreshToken rt SET rt.revoked = true WHERE rt.user.userId = :userId")
    void revokeAllByUserId(@Param("userId") Long userId);

    /** Ends one candidate's examination sessions for one paper, and nothing else of theirs. */
    @Modifying
    @Query("UPDATE RefreshToken rt SET rt.revoked = true "
        + "WHERE rt.user.userId = :userId AND rt.examId = :examId")
    void revokeExamSessions(@Param("userId") Long userId, @Param("examId") Long examId);

    /**
     * Ends the ordinary (account-password) sessions of these people, leaving their examination
     * sessions alone. Admins are left alone too: they are not candidates.
     */
    @Modifying
    @Query("UPDATE RefreshToken rt SET rt.revoked = true "
        + "WHERE rt.examId IS NULL AND rt.revoked = false AND rt.user.userId IN "
        + "(SELECT u.userId FROM User u WHERE u.userId IN :userIds AND u.role = 'USER')")
    int revokeOrdinarySessions(@Param("userIds") java.util.Collection<Long> userIds);
}
