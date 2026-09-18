package com.cpintel.admin;

import com.cpintel.entity.SyncJob;
import com.cpintel.files.ContestFilePolicy;
import com.cpintel.repository.jpa.SyncJobRepository;
import com.cpintel.repository.jpa.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.aggregation.AggregationResults;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;

/**
 * The numbers behind the landing screen.
 *
 * Every figure here is a count or a sum computed by the database, so this stays a handful of
 * cheap queries rather than something that has to be cached as the deployment grows.
 *
 * The file totals are sizes and owner counts only. An admin can see that the vault is filling
 * up; nothing on this screen — or anywhere else in the console — lets them see whose file is
 * whose, or read one.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AdminOverviewService {

    private static final int RECENT_ACTIVITY_ROWS = 12;

    private final UserRepository userRepository;
    private final SyncJobRepository syncJobRepository;
    private final ContestFilePolicy filePolicy;
    private final AdminAuditService auditService;
    private final MongoTemplate mongoTemplate;

    public AdminDto.Overview overview() {
        Instant weekAgo = Instant.now().minus(Duration.ofDays(7));
        Instant dayAgo = Instant.now().minus(Duration.ofDays(1));

        long total = userRepository.count();
        long active = userRepository.countByIsActive(true);

        var users = new AdminDto.UserStats(
            total,
            active,
            total - active,
            userRepository.countByRole("ADMIN"),
            userRepository.countByIsVerified(true),
            userRepository.countByCreatedAtAfter(weekAgo));

        var sync = new AdminDto.SyncStats(
            syncJobRepository.countByStatus(SyncJob.Status.QUEUED.name()),
            syncJobRepository.countByStatus(SyncJob.Status.RUNNING.name()),
            syncJobRepository.countByStatusAndCompletedAtAfter(SyncJob.Status.FAILED.name(), dayAgo),
            syncJobRepository.countByStatusAndCompletedAtAfter(SyncJob.Status.COMPLETED.name(), dayAgo));

        return new AdminDto.Overview(
            users,
            sync,
            fileStats(),
            policyStats(),
            auditService.recent(RECENT_ACTIVITY_ROWS));
    }

    /**
     * The file policy as a headline figure. Listing the exceptions needs the rule store; the
     * default in force does not, so a store that is down still reports the answer contestants
     * would actually get, with the exception count left at zero.
     */
    private AdminDto.PolicyStats policyStats() {
        try {
            var policy = filePolicy.overview();
            return new AdminDto.PolicyStats(
                policy.defaultEnabled(), policy.defaultFromConfig(), policy.rules().size());
        } catch (Exception e) {
            log.warn("Could not read contest file rules: {}", e.getMessage());
            return new AdminDto.PolicyStats(filePolicy.defaultEnabled(), true, 0);
        }
    }

    /**
     * File totals, or zeroes with {@code available=false} if the store cannot be reached.
     *
     * The overview is the screen an admin opens when something looks wrong, which is exactly
     * when a dependency might be down. Reporting the part that could not be read is more
     * useful than failing the whole page, and far more useful than showing a confident zero.
     */
    private AdminDto.FileStats fileStats() {
        try {
            var aggregation = Aggregation.newAggregation(
                Aggregation.group()
                    .count().as("files")
                    .sum("sizeBytes").as("bytes")
                    .addToSet("userId").as("owners"));

            AggregationResults<org.bson.Document> results =
                mongoTemplate.aggregate(aggregation, "personal_files", org.bson.Document.class);

            org.bson.Document row = results.getUniqueMappedResult();
            if (row == null) return new AdminDto.FileStats(0, 0, 0, true);

            var owners = row.getList("owners", Object.class);
            return new AdminDto.FileStats(
                asLong(row.get("files")),
                asLong(row.get("bytes")),
                owners == null ? 0 : owners.size(),
                true);
        } catch (Exception e) {
            log.warn("Could not read personal file totals: {}", e.getMessage());
            return new AdminDto.FileStats(0, 0, 0, false);
        }
    }

    private long asLong(Object value) {
        return value instanceof Number number ? number.longValue() : 0L;
    }
}
