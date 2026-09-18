package com.cpintel.repository.jpa;

import com.cpintel.entity.PlatformAccount;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PlatformAccountRepository extends JpaRepository<PlatformAccount, Long> {
    List<PlatformAccount> findByUserUserId(Long userId);
    Optional<PlatformAccount> findByUserUserIdAndPlatform(Long userId, String platform);
    boolean existsByUserUserIdAndPlatform(Long userId, String platform);

    @Query("SELECT pa FROM PlatformAccount pa WHERE pa.syncStatus = 'PENDING' OR pa.syncStatus = 'FAILED'")
    List<PlatformAccount> findAccountsPendingSync();

    @Modifying
    @Query("UPDATE PlatformAccount pa SET pa.syncStatus = :status WHERE pa.accountId = :id")
    void updateSyncStatus(@Param("id") Long id, @Param("status") String status);

    /**
     * The three fields the nightly sync needs, for active accounts only, a page at a time.
     *
     * Replaces {@code findAll()} followed by an {@code isActive} check in the loop, which loaded
     * the whole table — including the accounts it was about to skip — and joined each one to its
     * user to read an id. A projection avoids both: the filter is in the query, and no entity is
     * managed for a row the job only needs three columns of.
     */
    @Query("SELECT pa.accountId AS accountId, pa.user.userId AS userId, pa.platform AS platform "
         + "FROM PlatformAccount pa WHERE pa.isActive = true ORDER BY pa.accountId")
    Slice<ActiveAccount> findActiveAccounts(Pageable pageable);

    /** Projection for {@link #findActiveAccounts}. */
    interface ActiveAccount {
        Long getAccountId();
        Long getUserId();
        String getPlatform();
    }
}
