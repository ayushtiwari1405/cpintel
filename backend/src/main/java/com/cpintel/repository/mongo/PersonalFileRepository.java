package com.cpintel.repository.mongo;

import com.cpintel.entity.mongo.PersonalFile;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PersonalFileRepository extends MongoRepository<PersonalFile, String> {

    /**
     * The vault listing, with the bytes left in the database.
     *
     * Every screen that shows the file list needs names and sizes and nothing else, so the
     * content field is projected away — otherwise opening the panel would drag every
     * uploaded PDF across the wire to render a list of filenames.
     */
    @Query(value = "{ 'userId': ?0 }", fields = "{ 'content': 0 }", sort = "{ 'name': 1 }")
    List<PersonalFile> listMetadata(Long userId);

    /** Ownership is part of the lookup, so a wrong id reads as "not found", never as someone
     *  else's file. */
    Optional<PersonalFile> findByIdAndUserId(String id, Long userId);

    Optional<PersonalFile> findByUserIdAndName(Long userId, String name);

    long countByUserId(Long userId);

    void deleteByUserId(Long userId);
}
