package com.cpintel.repository.mongo;

import com.cpintel.entity.mongo.CodeSubmission;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface CodeSubmissionRepository extends MongoRepository<CodeSubmission, String> {

    List<CodeSubmission> findByUserIdAndPlatformAndContestIdAndProblemIndexOrderBySubmittedAtDesc(
        Long userId, String platform, String contestId, String problemIndex);

    List<CodeSubmission> findByUserIdAndPlatformAndContestId(
        Long userId, String platform, String contestId);

    Optional<CodeSubmission> findByUserIdAndPlatformAndExternalId(
        Long userId, String platform, Long externalId);

    List<CodeSubmission> findByUserIdOrderBySubmittedAtDesc(Long userId, Pageable pageable);

    void deleteByUserId(Long userId);
}
