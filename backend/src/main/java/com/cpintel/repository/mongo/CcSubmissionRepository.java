package com.cpintel.repository.mongo;

import com.cpintel.entity.mongo.CcSubmission;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface CcSubmissionRepository extends MongoRepository<CcSubmission, String> {
    List<CcSubmission> findByUserId(Long userId);
    long countByUserIdAndResult(Long userId, String result);

    @Query("{ 'userId': ?0, 'result': 'AC' }")
    List<CcSubmission> findAcceptedByUserId(Long userId);

    @Query("{ 'userId': ?0, 'normalized': false }")
    List<CcSubmission> findUnnormalized(Long userId);

    void deleteByUserId(Long userId);
}
