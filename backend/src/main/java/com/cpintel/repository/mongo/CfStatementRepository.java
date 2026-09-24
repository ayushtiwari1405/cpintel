package com.cpintel.repository.mongo;

import com.cpintel.entity.mongo.CfStatementDoc;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface CfStatementRepository extends MongoRepository<CfStatementDoc, String> {
}
