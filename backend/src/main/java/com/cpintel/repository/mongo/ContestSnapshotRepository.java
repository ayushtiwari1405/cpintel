package com.cpintel.repository.mongo;

import com.cpintel.entity.mongo.ContestSnapshotDoc;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ContestSnapshotRepository extends MongoRepository<ContestSnapshotDoc, String> {
    List<ContestSnapshotDoc> findByUserId(Long userId);
    List<ContestSnapshotDoc> findByUserIdAndPlatform(Long userId, String platform);
}
