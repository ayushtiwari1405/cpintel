package com.cpintel.repository.mongo;

import com.cpintel.entity.mongo.LcSubmission;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface LcSubmissionRepository extends MongoRepository<LcSubmission, String> {
    List<LcSubmission> findByUserId(Long userId);
    Optional<LcSubmission> findByLcSubmissionId(Long lcSubmissionId);
    boolean existsByLcSubmissionId(Long lcSubmissionId);

    @Query("{ 'userId': ?0, 'status': 'Accepted' }")
    List<LcSubmission> findAcceptedByUserId(Long userId);

    @Query("{ 'userId': ?0, 'difficulty': ?1, 'status': 'Accepted' }")
    List<LcSubmission> findAcceptedByDifficulty(Long userId, String difficulty);

    @Query("{ 'userId': ?0, 'normalized': false }")
    List<LcSubmission> findUnnormalized(Long userId);

    void deleteByUserId(Long userId);
}
