package com.cpintel.repository.jpa;

import com.cpintel.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByEmail(String email);
    Optional<User> findByUsername(String username);
    boolean existsByEmail(String email);
    boolean existsByUsername(String username);

    @Query("SELECT u FROM User u LEFT JOIN FETCH u.platformAccounts WHERE u.userId = :id")
    Optional<User> findByIdWithPlatforms(@Param("id") Long id);

    @Query("SELECT u FROM User u LEFT JOIN FETCH u.unifiedScore WHERE u.userId = :id")
    Optional<User> findByIdWithScore(@Param("id") Long id);
}
