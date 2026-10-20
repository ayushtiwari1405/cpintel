package com.cpintel.repository;

import com.cpintel.analytics.MaterializedViewRefresher;
import com.cpintel.evaluation.EvaluationLocks;
import com.cpintel.entity.Classroom;
import com.cpintel.repository.jpa.ClassroomRepository;
import com.cpintel.repository.jpa.ContestSummaryRepository;
import com.cpintel.repository.jpa.ExamTaAssignmentRepository;
import com.cpintel.repository.jpa.RecommendationRepository;
import com.cpintel.repository.jpa.RevisionScheduleRepository;
import com.cpintel.repository.jpa.SyncJobRepository;
import com.cpintel.repository.jpa.TopicMasteryRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The schema against the database it was written for.
 *
 * <p>Every other test mocks the repositories, so until now the migrations, the entity mappings
 * and the hand-written SQL were only ever proven by starting the application. Here an empty
 * Postgres 16 — the image Compose runs — gets every Flyway migration, Hibernate validates each
 * entity against the result, and each native query runs on real rows.
 *
 * <p>Needs Docker. Skipped on a machine without it, but never in CI: there a missing Docker
 * fails the run instead of quietly dropping the only test of the schema.
 */
@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=validate")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({EvaluationLocks.class, MaterializedViewRefresher.class})
@Testcontainers
@DisabledIf("noDockerOutsideCi")
class PostgresSchemaTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    static boolean noDockerOutsideCi() {
        return System.getenv("CI") == null && !DockerClientFactory.instance().isDockerAvailable();
    }

    @Autowired private JdbcTemplate jdbc;
    @Autowired private ClassroomRepository classrooms;
    @Autowired private TopicMasteryRepository mastery;
    @Autowired private RevisionScheduleRepository revisions;
    @Autowired private RecommendationRepository recommendations;
    @Autowired private ContestSummaryRepository contests;
    @Autowired private ExamTaAssignmentRepository taAssignments;
    @Autowired private SyncJobRepository syncJobs;
    @Autowired private EvaluationLocks locks;
    @Autowired private MaterializedViewRefresher views;

    // ------------------------------------------------------------------- fixtures

    private long user(String name) {
        return jdbc.queryForObject("""
            INSERT INTO users (username, email, password_hash) VALUES (?, ?, 'x')
            RETURNING user_id
            """, Long.class, name, name + "@example.test");
    }

    private long classroom(String name, Long ownerId) {
        return jdbc.queryForObject(
            "INSERT INTO classrooms (name, owner_id) VALUES (?, ?) RETURNING classroom_id",
            Long.class, name, ownerId);
    }

    /** A DOMjudge event; its external id carries the classroom prefix chk_gc_domjudge_ref asks for. */
    private long event(long classroomId, String contest) {
        return jdbc.queryForObject("""
            INSERT INTO group_contests (classroom_id, platform, external_id, name)
            VALUES (?, 'DOMJUDGE', ?, ?) RETURNING contest_id
            """, Long.class, classroomId, classroomId + "~" + contest, "Event " + contest);
    }

    private int count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Integer.class, args);
    }

    // ------------------------------------------------------------------- migrations

    @Test
    @DisplayName("every migration on the classpath is applied, and none failed")
    void allMigrationsApplied() throws IOException {
        int files = new PathMatchingResourcePatternResolver()
            .getResources("classpath:db/migration/V*.sql").length;

        assertThat(files).isPositive();
        assertThat(count("SELECT count(*) FROM flyway_schema_history WHERE success")).isEqualTo(files);
        assertThat(count("SELECT count(*) FROM flyway_schema_history WHERE NOT success")).isZero();
    }

    // ------------------------------------------------------------------- classrooms

    @Test
    @DisplayName("classroom staff: managed-by covers owners and staff, and add/remove are idempotent")
    void classroomStaff() {
        long owner = user("owner");
        long staff = user("staff");
        long stranger = user("stranger");
        long owned = classroom("B owned", owner);
        long staffed = classroom("A staffed", null);

        classrooms.addStaff(staffed, staff);
        classrooms.addStaff(staffed, staff);
        classrooms.addStaff(owned, staff);

        assertThat(classrooms.findManagedBy(owner)).extracting(Classroom::getName).containsExactly("B owned");
        assertThat(classrooms.findManagedBy(staff)).extracting(Classroom::getName)
            .containsExactly("A staffed", "B owned");
        assertThat(classrooms.findManagedBy(stranger)).isEmpty();

        assertThat(classrooms.isStaff(staffed, staff)).isTrue();
        assertThat(classrooms.isStaff(staffed, stranger)).isFalse();
        assertThat(classrooms.staffIds(staffed)).containsExactly(staff);

        classrooms.removeStaff(staffed, staff);
        assertThat(classrooms.isStaff(staffed, staff)).isFalse();
        assertThat(classrooms.staffIds(staffed)).isEmpty();
    }

    @Test
    @DisplayName("classroom TAs: add reports whether a row was written, lookups agree")
    void classroomTas() {
        long admin = user("admin");
        long ta = user("ta");
        long room = classroom("Room", null);
        long other = classroom("Other", null);

        assertThat(classrooms.addTa(room, ta, admin)).isEqualTo(1);
        assertThat(classrooms.addTa(room, ta, admin)).isZero();

        assertThat(classrooms.isTa(room, ta)).isTrue();
        assertThat(classrooms.isTa(other, ta)).isFalse();
        assertThat(classrooms.isTaInAny(ta, List.of(other, room))).isTrue();
        assertThat(classrooms.isTaInAny(ta, List.of(other))).isFalse();
        assertThat(classrooms.taIds(room)).containsExactly(ta);
        assertThat(classrooms.taClassroomIds(ta)).containsExactly(room);

        assertThat(classrooms.removeTa(room, ta)).isEqualTo(1);
        assertThat(classrooms.removeTa(room, ta)).isZero();
    }

    // ------------------------------------------------------------------- analytics

    @Test
    @DisplayName("topic mastery upsert inserts once, then updates the same row")
    void topicMasteryUpsert() {
        long u = user("solver");
        Instant at = Instant.parse("2026-10-01T10:00:00Z");

        mastery.upsertCounts(u, "dp", "NODE", "dynamic programming", "CORE",
            3, 5, at, 1500, null, null, null, null);
        mastery.upsertCounts(u, "dp", "NODE", "dynamic programming", "CORE",
            4, 6, at, 1600, 1550.0, 90.0, 4, "CODEFORCES");

        Map<String, Object> row = jdbc.queryForMap(
            "SELECT problems_solved, problems_attempted, max_solved_rating, skill_basis"
                + " FROM topic_mastery WHERE user_id = ?", u);
        assertThat(row).containsEntry("problems_solved", 4)
            .containsEntry("problems_attempted", 6)
            .containsEntry("max_solved_rating", 1600)
            .containsEntry("skill_basis", "CODEFORCES");
    }

    @Test
    @DisplayName("due revisions: only past-due, highest priority first")
    void dueRevisions() {
        long u = user("reviser");
        jdbc.update("""
            INSERT INTO revision_schedule (user_id, topic, next_revision_at, revision_priority) VALUES
              (?, 'low',    now() - interval '1 day', 10),
              (?, 'high',   now() - interval '1 hour', 90),
              (?, 'future', now() + interval '1 day', 99)
            """, u, u, u);

        assertThat(revisions.findDueRevisions(u))
            .extracting(r -> r.getTopic()).containsExactly("high", "low");
    }

    @Test
    @DisplayName("latest active recommendation skips consumed and expired ones")
    void latestActiveRecommendation() {
        long u = user("recommended");
        jdbc.update("""
            INSERT INTO recommendations (user_id, rec_type, generated_at, expires_at, is_consumed) VALUES
              (?, 'DAILY', now() - interval '3 hour', NULL,                     false),
              (?, 'DAILY', now() - interval '2 hour', now() - interval '1 hour', false),
              (?, 'DAILY', now() - interval '1 hour', NULL,                     true),
              (?, 'WEEKLY', now(),                    NULL,                     false)
            """, u, u, u, u);
        long expected = count("""
            SELECT rec_id FROM recommendations
             WHERE user_id = ? AND rec_type = 'DAILY' AND expires_at IS NULL AND NOT is_consumed
            """, u);

        assertThat(recommendations.findLatestActiveByUserAndType(u, "DAILY"))
            .hasValueSatisfying(r -> assertThat(r.getRecId()).isEqualTo(expected));
        assertThat(recommendations.findLatestActiveByUserAndType(u, "REVISION")).isEmpty();
    }

    @Test
    @DisplayName("recent contests are newest first and limited; the average change is per platform")
    void contestSummaries() {
        long u = user("contestant");
        jdbc.update("""
            INSERT INTO contest_summaries (user_id, platform, contest_name, rating_change, contest_date) VALUES
              (?, 'CODEFORCES', 'old',    10, now() - interval '30 day'),
              (?, 'CODEFORCES', 'mid',   -20, now() - interval '10 day'),
              (?, 'CODEFORCES', 'new',    40, now() - interval '1 day'),
              (?, 'DOMJUDGE',   'judge', 100, now())
            """, u, u, u, u);

        assertThat(contests.findRecentContests(u, 2))
            .extracting(c -> c.getContestName()).containsExactly("judge", "new");
        assertThat(contests.getAvgRatingChange(u, "CODEFORCES")).isEqualTo(10.0);
        assertThat(contests.getAvgRatingChange(u, "NONE")).isNull();
    }

    @Test
    @DisplayName("latest sync job is per user and platform")
    void latestSyncJob() {
        long u = user("syncer");
        jdbc.update("""
            INSERT INTO sync_jobs (user_id, platform, status, created_at) VALUES
              (?, 'CODEFORCES', 'COMPLETED', now() - interval '1 day'),
              (?, 'CODEFORCES', 'FAILED',    now())
            """, u, u);

        assertThat(syncJobs.findLatestByUserAndPlatform(u, "CODEFORCES"))
            .hasValueSatisfying(j -> assertThat(j.getStatus()).isEqualTo("FAILED"));
        assertThat(syncJobs.findLatestByUserAndPlatform(u, "DOMJUDGE")).isEmpty();
    }

    // ------------------------------------------------------------------- evaluation

    @Test
    @DisplayName("TA assignments and freezes are cleared for one classroom only")
    void classroomScopedTaCleanup() {
        long ta = user("marker");
        long room = classroom("Room", null);
        long other = classroom("Other", null);
        long here = event(room, "here");
        long there = event(other, "there");
        jdbc.update("INSERT INTO exam_ta_assignments (contest_id, ta_user_id) VALUES (?, ?), (?, ?)",
            here, ta, there, ta);
        locks.freeze(here, ta);
        locks.freeze(there, ta);

        assertThat(taAssignments.deleteInClassroom(room, ta)).isEqualTo(1);
        locks.unfreezeInClassroom(room, ta);

        assertThat(count("SELECT count(*) FROM exam_ta_assignments WHERE ta_user_id = ?", ta)).isEqualTo(1);
        assertThat(locks.freezes(here)).isEmpty();
        assertThat(locks.freezes(there)).containsOnlyKeys(ta);
    }

    @Test
    @DisplayName("freeze, reopen and the change history round-trip through Postgres")
    void evaluationLocks() {
        long admin = user("examiner");
        long ta = user("assistant");
        long student = user("candidate");
        long exam = event(classroom("Room", null), "paper");

        locks.freeze(exam, ta);
        locks.freeze(exam, ta);
        assertThat(locks.freezes(exam)).containsOnlyKeys(ta);
        assertThat(locks.unfreeze(exam, ta)).isTrue();
        assertThat(locks.unfreeze(exam, ta)).isFalse();

        locks.reopen(exam, student, "A", admin);
        locks.reopen(exam, student, "A", admin);
        locks.reopen(exam, student, "B", admin);
        assertThat(locks.reopened(exam)).containsExactlyInAnyOrder(
            EvaluationLocks.key(student, "A"), EvaluationLocks.key(student, "B"));
        locks.closeAll(exam, List.of(EvaluationLocks.key(student, "A")));
        assertThat(locks.close(exam, student, "A")).isFalse();
        assertThat(locks.reopened(exam)).containsExactly(EvaluationLocks.key(student, "B"));

        locks.recordChange(exam, ta, student, "A", EvaluationLocks.REOPENED, admin);
        locks.recordChange(exam, ta, student, "A", EvaluationLocks.REOPENED, admin);
        locks.recordChange(exam, ta, student, "B", EvaluationLocks.ADMIN_CHANGED, admin);
        assertThat(locks.changeCounts(exam)).containsEntry(ta,
            Map.of(EvaluationLocks.REOPENED, 1, EvaluationLocks.ADMIN_CHANGED, 1));
        assertThat(locks.totalChanges(exam)).containsEntry(ta, 2);
    }

    // ------------------------------------------------------------------- reporting views

    @Test
    @DisplayName("materialized views populate, and then refresh concurrently")
    void materializedViews() {
        views.refreshAll();

        assertThat(count("SELECT count(*) FROM pg_matviews WHERE NOT ispopulated")).isZero();
        for (String view : jdbc.queryForList("SELECT matviewname FROM pg_matviews", String.class)) {
            // CONCURRENTLY needs a unique index on each view; refreshAll would only log its absence.
            jdbc.execute("REFRESH MATERIALIZED VIEW CONCURRENTLY " + view);
        }
    }
}
