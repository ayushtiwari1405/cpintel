package com.cpintel.repository;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledIf;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V36 to V38 meet data the application used to accept: each test fills a schema at V35 with it,
 * then upgrades.
 */
@Testcontainers
@DisabledIf("noDockerOutsideCi")
class DefectRepairMigrationsTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    static boolean noDockerOutsideCi() {
        return System.getenv("CI") == null && !DockerClientFactory.instance().isDockerAvailable();
    }

    private final DataSource source = new DriverManagerDataSource(POSTGRES.getJdbcUrl(),
        POSTGRES.getUsername(), POSTGRES.getPassword());
    private final JdbcTemplate jdbc = new JdbcTemplate(source);

    private Flyway flyway(MigrationVersion target) {
        return Flyway.configure().dataSource(source).locations("classpath:db/migration")
            .target(target).cleanDisabled(false).load();
    }

    private void schemaBeforeTheRepairs() {
        flyway(MigrationVersion.LATEST).clean();
        flyway(MigrationVersion.fromVersion("35")).migrate();
    }

    private long user(String name, String email, String avatar) {
        return jdbc.queryForObject("""
            INSERT INTO users (username, email, password_hash, avatar_url) VALUES (?, ?, 'x', ?)
            RETURNING user_id
            """, Long.class, name, email, avatar);
    }

    private long classroom() {
        return jdbc.queryForObject(
            "INSERT INTO classrooms (name) VALUES ('Room') RETURNING classroom_id", Long.class);
    }

    @Test
    @DisplayName("accounts differing only in capitals stop the upgrade, named by id")
    void duplicateAccountsStopTheUpgrade() {
        schemaBeforeTheRepairs();
        long first = user("stu_ab", "a@example.test", null);
        long second = user("STU_AB", "b@example.test", null);

        assertThatThrownBy(() -> flyway(MigrationVersion.LATEST).migrate())
            .hasMessageContaining("username of users " + first + ", " + second);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM users", Integer.class)).isEqualTo(2);
    }

    @Test
    @DisplayName("unsat events go back to draft, bad avatars are cleared, shared team names are suffixed")
    void repairs() {
        schemaBeforeTheRepairs();
        long owner = user("owner", "owner@example.test", "https://example.test/a.png");
        long scripted = user("scripted", "s@example.test", "javascript:alert(1)");
        long room = classroom();
        String team = "INSERT INTO contest_groups (classroom_id, name, owner_id) VALUES (?, ?, ?) RETURNING group_id";
        long first = jdbc.queryForObject(team, Long.class, room, "Alpha", owner);
        long second = jdbc.queryForObject(team, Long.class, room, "alpha", owner);
        long longName = jdbc.queryForObject(team, Long.class, room, "b".repeat(120), owner);
        long longTwin = jdbc.queryForObject(team, Long.class, room, "B".repeat(120), owner);

        // start, end and archive, in hours from now.
        long endedDraft = event(room, "1", "SCHEDULED", 24, -1, null);
        long archivedUnstarted = event(room, "2", "ARCHIVED", 24, 25, -1);
        long archivedNoWindow = event(room, "3", "ARCHIVED", null, null, -1);
        long sat = event(room, "4", "ARCHIVED", -48, -47, -1);
        long upcoming = event(room, "5", "SCHEDULED", 24, 25, null);

        flyway(MigrationVersion.LATEST).migrate();

        assertThat(lifecycle(endedDraft)).isEqualTo("DRAFT");
        assertThat(lifecycle(archivedUnstarted)).isEqualTo("DRAFT");
        assertThat(lifecycle(archivedNoWindow)).isEqualTo("DRAFT");
        assertThat(lifecycle(sat)).isEqualTo("ARCHIVED");
        assertThat(lifecycle(upcoming)).isEqualTo("SCHEDULED");

        assertThat(avatar(owner)).isEqualTo("https://example.test/a.png");
        assertThat(avatar(scripted)).isNull();

        assertThat(name(first)).isEqualTo("Alpha");
        assertThat(name(second)).isEqualTo("alpha (" + second + ")");
        assertThat(name(longName)).hasSize(120);
        assertThat(name(longTwin)).hasSize(120).endsWith(" (" + longTwin + ")");
    }

    private long event(long room, String contest, String lifecycle, Integer start, Integer end,
                       Integer archived) {
        return jdbc.queryForObject("""
            INSERT INTO group_contests (classroom_id, platform, external_id, name, lifecycle,
                                        starts_at, ends_at, archived_at)
            VALUES (?, 'DOMJUDGE', ?, 'Event', ?, now() + make_interval(hours => ?::int),
                    now() + make_interval(hours => ?::int), now() + make_interval(hours => ?::int))
            RETURNING contest_id
            """, Long.class, room, room + "~" + contest, lifecycle, start, end, archived);
    }

    private String lifecycle(long id) {
        return jdbc.queryForObject("SELECT lifecycle FROM group_contests WHERE contest_id = ?",
            String.class, id);
    }

    private String avatar(long id) {
        return jdbc.queryForObject("SELECT avatar_url FROM users WHERE user_id = ?", String.class, id);
    }

    private String name(long id) {
        return jdbc.queryForObject("SELECT name FROM contest_groups WHERE group_id = ?",
            String.class, id);
    }
}
