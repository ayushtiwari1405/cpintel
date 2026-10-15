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
    /** Per user: the same Codeforces submission is stored once for each account linked to it. */
    boolean existsByUserIdAndCfSubmissionId(Long userId, Long cfSubmissionId);
    long countByUserIdAndVerdict(Long userId, String verdict);

    /**
     * Which of these submissions are already stored, with the one field a re-sync may still
     * have to fill in. One query per page of history, rather than one per submission.
     */
    @Query(value = "{ 'userId': ?0, 'cfSubmissionId': { $in: ?1 } }",
           fields = "{ 'cfSubmissionId': 1, 'participantType': 1 }")
    List<CfSubmission> findKnown(Long userId, java.util.Collection<Long> cfSubmissionIds);

    @Query("{ 'userId': ?0, 'verdict': 'OK', 'tags': { $in: ?1 } }")
    List<CfSubmission> findSolvedByUserIdAndTags(Long userId, List<String> tags);

    @Query("{ 'userId': ?0, 'submittedAt': { $gte: ?1 } }")
    List<CfSubmission> findByUserIdAfter(Long userId, Instant after);

    @Query("{ 'userId': ?0, 'normalized': false }")
    List<CfSubmission> findUnnormalized(Long userId);

    void deleteByUserId(Long userId);
}
