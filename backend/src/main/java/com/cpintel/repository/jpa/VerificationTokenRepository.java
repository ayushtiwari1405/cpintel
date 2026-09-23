package com.cpintel.repository.jpa;

import com.cpintel.entity.VerificationToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;

@Repository
public interface VerificationTokenRepository extends JpaRepository<VerificationToken, Long> {

    /**
     * Finds a token by the hash of what the user presented.
     *
     * The raw token is never stored — see PasswordService — so this is the only lookup there
     * is, and a read of this table yields nothing anybody can redeem.
     */
    Optional<VerificationToken> findByToken(String token);

    /**
     * Retires every outstanding token of a type for one account.
     *
     * Called when a new one is issued and again when a password actually changes. The first
     * keeps a mailbox full of reset links from all being live at once; the second is what
     * makes a completed reset close the door behind it, including on links somebody else
     * requested.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE VerificationToken t SET t.used = true "
         + "WHERE t.user.userId = :userId AND t.tokenType = :type AND t.used = false")
    int invalidateOutstanding(@Param("userId") Long userId, @Param("type") String type);

    /** Expired and spent tokens are of no further interest; the sweep drops them. */
    @Modifying
    @Query("DELETE FROM VerificationToken t WHERE t.expiresAt < :before OR t.used = true")
    int deleteSpent(@Param("before") Instant before);
}
