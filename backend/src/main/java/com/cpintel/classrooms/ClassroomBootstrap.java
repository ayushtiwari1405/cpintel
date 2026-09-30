package com.cpintel.classrooms;

import com.cpintel.entity.Classroom;
import com.cpintel.entity.mongo.CodeSubmission;
import com.cpintel.entity.mongo.ContestFileRule;
import com.cpintel.integration.domjudge.DomjudgeCredentialStore;
import com.cpintel.integration.domjudge.JudgeContestRef;
import com.cpintel.repository.jpa.ClassroomRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Component;


/**
 * Finishes moving a deployment from before classrooms into the one V16 made, once, at startup.
 *
 * <p>V16 builds that classroom from what is already in the database: every existing team and
 * event goes into it, and everyone in them is enrolled. It has no judge yet; an admin sets its
 * DOMjudge URL from the console, and nothing is read from the environment. What SQL cannot
 * reach is moved here, and each step does nothing once done:
 *
 * <ol>
 *   <li>DOMjudge logins still in Redis move into Postgres: ones stored per user before
 *       classrooms go into that classroom, per-classroom ones into their own.</li>
 *   <li>Archived submissions and file rules that name a bare DOMjudge contest id are
 *       qualified with that classroom, matching what V16 did to the events.</li>
 * </ol>
 *
 * <p>That classroom is the oldest one. A deployment with no classrooms had no teams or events
 * either, so there is nothing of the old kind to move.
 *
 * <p>Ordered first, so {@code ContestFileRuleBackfill} sees qualified ids.
 */
@Component
@Order(0)
@RequiredArgsConstructor
@Slf4j
public class ClassroomBootstrap implements ApplicationRunner {

    private final ClassroomRepository classrooms;
    private final DomjudgeCredentialStore credentials;
    private final MongoTemplate mongo;

    @Override
    public void run(ApplicationArguments args) {
        try {
            Classroom first = classrooms.findAll().stream()
                .min(java.util.Comparator.comparing(Classroom::getClassroomId))
                .orElse(null);
            if (first == null) return;
            adoptLogins(first.getClassroomId());
            qualifyArchive(first.getClassroomId());
        } catch (Exception e) {
            log.warn("Could not finish moving to classrooms: {}", e.getMessage());
        }
    }

    private void adoptLogins(Long classroomId) {
        credentials.adoptFromRedis(classroomId);
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
