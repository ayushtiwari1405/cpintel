package com.cpintel.repository.mongo;

import com.cpintel.entity.mongo.CfSubmission;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public interface CfSubmissionRepository extends MongoRepository<CfSubmission, String> {
    List<CfSubmission> findByUserId(Long userId);
    Optional<CfSubmission> findByCfSubmissionId(Long cfSubmissionId);
    boolean existsByCfSubmissionId(Long cfSubmissionId);
    long countByUserIdAndVerdict(Long userId, String verdict);

    @Query("{ 'userId': ?0, 'verdict': 'OK', 'tags': { $in: ?1 } }")
    List<CfSubmission> findSolvedByUserIdAndTags(Long userId, List<String> tags);

    @Query("{ 'userId': ?0, 'submittedAt': { $gte: ?1 } }")
    List<CfSubmission> findByUserIdAfter(Long userId, Instant after);

    @Query("{ 'userId': ?0, 'normalized': false }")
    List<CfSubmission> findUnnormalized(Long userId);
}
