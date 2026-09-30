package com.cpintel.classrooms;

import com.cpintel.entity.Classroom;
import com.cpintel.entity.mongo.CodeSubmission;
import com.cpintel.entity.mongo.ContestFileRule;
import com.cpintel.integration.domjudge.DomjudgeCredentialStore;
import com.cpintel.integration.domjudge.JudgeContestRef;
import com.cpintel.repository.jpa.ClassroomRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * Carries a deployment from the single-judge era into classrooms, once, at startup.
 *
 * <p>V16 moved every existing team and event into one classroom but could not give it a judge,
 * because the judge was named in the environment and a SQL migration cannot read that. This
 * finishes the job, and each step is a no-op once done:
 *
 * <ol>
 *   <li>The classroom left without a URL gets {@code CPINTEL_DOMJUDGE_URL}, and the service
 *       account beside it. On a fresh deployment with no classrooms at all, those settings
 *       become the first classroom, so an operator who set them is not left with nothing.</li>
 *   <li>Logins stored per user ({@code dj:cred:<user>}) move under that classroom.</li>
 *   <li>Archived submissions and file rules that name a bare DOMjudge contest id are
 *       qualified with that classroom, matching what V16 did to the events.</li>
 * </ol>
 *
 * <p>After this the {@code CPINTEL_DOMJUDGE_URL/USER/PASSWORD} variables are read by nothing
 * else; classrooms are managed from the admin console.
 *
 * <p>Ordered first, so {@code ContestFileRuleBackfill} sees qualified ids.
 */
@Component
@Order(0)
@RequiredArgsConstructor
@Slf4j
public class ClassroomBootstrap implements ApplicationRunner {

    private final ClassroomRepository classrooms;
    private final ClassroomService classroomService;
    private final DomjudgeCredentialStore credentials;
    private final MongoTemplate mongo;

    @Value("${cpintel.domjudge.base-url:}")
    private String legacyUrl;

    @Value("${cpintel.domjudge.username:}")
    private String legacyUsername;

    @Value("${cpintel.domjudge.password:}")
    private String legacyPassword;

    @Override
    public void run(ApplicationArguments args) {
        try {
            Classroom legacy = adoptEnvironment();
            if (legacy == null) return;
            Long id = legacy.getClassroomId();
            adoptLogins(id);
            qualifyArchive(id);
        } catch (Exception e) {
            log.warn("Could not finish moving to classrooms: {}", e.getMessage());
        }
    }

    /** The classroom the single-judge era's data belongs to, or null when there is none. */
    private Classroom adoptEnvironment() {
        List<Classroom> all = classrooms.findAll();
        String url = StringUtils.hasText(legacyUrl)
            ? ClassroomService.normaliseUrl(legacyUrl) : null;

        Classroom target = all.stream()
            .filter(c -> !StringUtils.hasText(c.getDomjudgeUrl()))
            .findFirst()
            .orElse(null);

        if (target == null && all.isEmpty() && url != null) {
            target = Classroom.builder().name("Default classroom")
                .description("Created from CPINTEL_DOMJUDGE_URL.").build();
        }
        if (target == null) {
            // Already migrated. The classroom on the environment's URL, if any, is still the
            // one legacy rows belong to.
            return url == null ? null : classrooms.findByDomjudgeUrl(url).orElse(null);
        }
        if (url == null) {
            log.warn("Classroom {} has no DOMjudge URL, and CPINTEL_DOMJUDGE_URL is not set. "
                + "Set its judge from the admin console.", target.getClassroomId());
            return target.getClassroomId() == null ? null : target;
        }
        if (classrooms.findByDomjudgeUrl(url).isPresent()) {
            log.warn("CPINTEL_DOMJUDGE_URL {} already belongs to another classroom; leaving "
                + "classroom {} without a judge.", url, target.getClassroomId());
            return target.getClassroomId() == null ? null : target;
        }

        target.setDomjudgeUrl(url);
        if (StringUtils.hasText(legacyUsername) && StringUtils.hasText(legacyPassword)) {
            if (credentials.isConfigured()) {
                target.setServiceUsername(legacyUsername.trim());
                target.setServicePassword(credentials.seal(legacyPassword));
            } else {
                log.warn("CPINTEL_DOMJUDGE_CREDENTIAL_KEY is not set, so the service account "
                    + "in CPINTEL_DOMJUDGE_USER cannot be stored on the classroom.");
            }
        }
        target = classrooms.save(target);
        log.info("Classroom {} now uses the judge at {}", target.getClassroomId(), url);
        return target;
    }

    private void adoptLogins(Long classroomId) {
        if (!credentials.isConfigured()) return;
        for (Long userId : credentials.adoptLegacy(classroomId)) {
            DomjudgeCredentialStore.Stored stored = credentials.find(classroomId, userId);
            try {
                classroomService.recordLogin(classroomId, userId,
                    stored == null ? null : stored.username());
            } catch (Exception e) {
                log.debug("Could not enrol user {} in classroom {}: {}",
                    userId, classroomId, e.getMessage());
            }
        }
    }

    private void qualifyArchive(Long classroomId) {
        Query bare = new Query(Criteria.where("platform").is("DOMJUDGE")
            .and("contestId").not().regex(String.valueOf(JudgeContestRef.SEPARATOR)));

        int submissions = 0;
        for (CodeSubmission row : mongo.find(bare, CodeSubmission.class)) {
            if (row.getContestId() == null) continue;
            row.setContestId(JudgeContestRef.encode(classroomId, row.getContestId()));
            mongo.save(row);
            submissions++;
        }

        int rules = 0;
        for (ContestFileRule rule : mongo.find(bare, ContestFileRule.class)) {
            if (rule.getContestId() == null) continue;
            String qualified = JudgeContestRef.encode(classroomId, rule.getContestId());
            Query existing = new Query(Criteria.where("platform").is("DOMJUDGE")
                .and("contestId").is(qualified));
            if (mongo.exists(existing, ContestFileRule.class)) {
                mongo.remove(rule);
            } else {
                rule.setContestId(qualified);
                mongo.save(rule);
            }
            rules++;
        }
        if (submissions + rules > 0) {
            log.info("Qualified {} archived submission(s) and {} file rule(s) with classroom {}",
                submissions, rules, classroomId);
        }
    }
}
