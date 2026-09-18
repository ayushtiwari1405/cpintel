package com.cpintel.repository.jpa;

import com.cpintel.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public interface UserRepository extends JpaRepository<User, Long>, JpaSpecificationExecutor<User> {
    Optional<User> findByEmail(String email);
    Optional<User> findByUsername(String username);
    boolean existsByEmail(String email);
    boolean existsByUsername(String username);

    @Query("SELECT u FROM User u LEFT JOIN FETCH u.platformAccounts WHERE u.userId = :id")
    Optional<User> findByIdWithPlatforms(@Param("id") Long id);

    @Query("SELECT u FROM User u LEFT JOIN FETCH u.unifiedScore WHERE u.userId = :id")
    Optional<User> findByIdWithScore(@Param("id") Long id);

    /**
     * Everything the dashboard mapper touches, in one query.
     *
     * The dashboard used to load with {@link #findByIdWithScore} and then map through
     * {@code toProfile}, which reads {@code platformAccounts} — not in that fetch graph. It
     * worked only because {@code open-in-view} kept a session open through serialisation and
     * quietly issued a second query. With that turned off it was the one endpoint in the
     * application that broke, and this is the fix: fetch what the mapper is going to read.
     *
     * <p>One collection and one to-one, so there is no MultipleBagFetchException to avoid.
     */
    @Query("SELECT u FROM User u "
         + "LEFT JOIN FETCH u.platformAccounts "
         + "LEFT JOIN FETCH u.unifiedScore "
         + "WHERE u.userId = :id")
    Optional<User> findByIdWithPlatformsAndScore(@Param("id") Long id);

    /** Ids only — the nightly score batch never needs the rest of the row. */
    @Query("SELECT u.userId FROM User u WHERE u.isActive = true")
    List<Long> findActiveUserIds();

    // ------------------------------------------------------ admin console counts

    long countByRole(String role);

    /**
     * Just the ids, a page at a time.
     *
     * The nightly analytics pass used {@code findAll()}, which materialised every user as a
     * managed entity before touching the first one — memory growing with the user count against
     * a fixed container limit. The pass only ever needs an id to hand to the service, so this
     * returns ids and nothing else, and the job's footprint stops depending on how many people
     * have signed up.
     */
    @Query("SELECT u.userId FROM User u ORDER BY u.userId")
    Slice<Long> findAllUserIds(Pageable pageable);

    long countByIsActive(Boolean isActive);

    long countByIsVerified(Boolean isVerified);

    long countByCreatedAtAfter(Instant since);

    /**
     * How many console accounts are left besides this one.
     *
     * Guards the two changes that could lock everybody out of the console: demoting the last
     * admin, and deactivating them. Asked as a count rather than a list because the answer
     * only ever needs to be compared against zero.
     *
     * Super admins count. They can open every screen an admin can, so a deployment whose last
     * remaining ADMIN is demoted while a SUPER_ADMIN is still active has not locked anybody
     * out — and refusing that demotion would be a false alarm.
     */
    @Query("SELECT COUNT(u) FROM User u WHERE u.role IN ('ADMIN', 'SUPER_ADMIN') "
         + "AND u.isActive = true AND u.userId <> :excludingUserId")
    long countOtherActiveAdmins(@Param("excludingUserId") Long excludingUserId);
}
