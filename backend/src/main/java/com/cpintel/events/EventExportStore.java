package com.cpintel.events;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bson.types.ObjectId;
import org.springframework.core.io.Resource;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.gridfs.GridFsTemplate;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.util.Optional;

/**
 * Where a finished event's export zip is kept.
 *
 * <p>GridFS, in the same database as the submissions it was built from: a zip of two hundred
 * people's code is past what one MongoDB document may hold, and keeping it there means the
 * existing backup already covers it and every backend instance can serve it.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class EventExportStore {

    private final GridFsTemplate gridFs;

    /** Stores the zip and answers the id to find it by. */
    public String save(String fileName, byte[] zip) {
        return gridFs.store(new ByteArrayInputStream(zip), fileName, "application/zip")
            .toHexString();
    }

    public Optional<Resource> open(String fileId) {
        if (fileId == null || !ObjectId.isValid(fileId)) return Optional.empty();
        var file = gridFs.findOne(byId(fileId));
        return file == null ? Optional.empty() : Optional.of(gridFs.getResource(file));
    }

    /** Best effort: a zip left behind costs disk, and must not fail whatever replaced it. */
    public void delete(String fileId) {
        if (fileId == null || !ObjectId.isValid(fileId)) return;
        try {
            gridFs.delete(byId(fileId));
        } catch (Exception e) {
            log.warn("Could not delete export file {}: {}", fileId, e.getMessage());
        }
    }

    private static Query byId(String fileId) {
        return new Query(Criteria.where("_id").is(new ObjectId(fileId)));
    }
}
