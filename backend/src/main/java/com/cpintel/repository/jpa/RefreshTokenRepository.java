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
    /** @param tokenDigest the token's {@link com.cpintel.common.TokenDigest}, never the token */
    Optional<RefreshToken> findByToken(String tokenDigest);

    /**
     * Spends one token, once. Returns 1 for the request that spent it and 0 for any other that
     * tried at the same moment.
     *
     * <p>The row is claimed by the update itself, not by reading it and writing it back: two
     * requests presenting the same token both read it as unspent, and a read-then-write lets both
     * of them rotate it and walk away with a live successor. Postgres makes the second update
     * wait for the first to commit and then finds the row no longer matches.
     */
    @Modifying
    @Query("UPDATE RefreshToken rt SET rt.revoked = true, rt.rotatedAt = :now "
        + "WHERE rt.tokenId = :tokenId AND rt.revoked = false")
    int claim(@Param("tokenId") Long tokenId, @Param("now") Instant now);

    /** Ends every session renewed from one sign-in. Returns how many were still live. */
    @Modifying
    @Query("UPDATE RefreshToken rt SET rt.revoked = true "
        + "WHERE rt.familyId = :familyId AND rt.revoked = false")
    int revokeFamily(@Param("familyId") String familyId);

    @Modifying
    @Query("DELETE FROM RefreshToken rt WHERE rt.user.userId = :userId")
    void deleteAllByUserId(@Param("userId") Long userId);

    @Modifying
    @Query("DELETE FROM RefreshToken rt WHERE rt.expiresAt < :now")
    int deleteExpiredTokens(@Param("now") Instant now);

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
