package com.cpintel.repository.mongo;

import com.cpintel.entity.mongo.ContestFileRule;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ContestFileRuleRepository extends MongoRepository<ContestFileRule, String> {

    Optional<ContestFileRule> findByPlatformAndContestId(String platform, String contestId);

    List<ContestFileRule> findAllByOrderByUpdatedAtDesc();

    void deleteByPlatformAndContestId(String platform, String contestId);
}
