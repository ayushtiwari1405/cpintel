package com.cpintel.repository.jpa;

import com.cpintel.entity.PlatformAccount;
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
}
