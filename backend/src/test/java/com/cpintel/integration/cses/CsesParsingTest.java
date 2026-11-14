package com.cpintel.integration.cses;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/** What CPIntel reads from CSES: times, graded submissions, and the task page. */
class CsesParsingTest {

    @Test
    void readsTimesWithOrWithoutAZone() {
        assertThat(CsesTime.parse("2026-11-12T10:00:00Z")).isEqualTo(Instant.parse("2026-11-12T10:00:00Z"));
        assertThat(CsesTime.parse("2026-11-12T12:00:00+02:00")).isEqualTo(Instant.parse("2026-11-12T10:00:00Z"));
        // No zone: Helsinki, two hours ahead of UTC in November.
        assertThat(CsesTime.parse("2026-11-12 12:00:00")).isEqualTo(Instant.parse("2026-11-12T10:00:00Z"));
        assertThat(CsesTime.parse("2026-11-12T12:00:00")).isEqualTo(Instant.parse("2026-11-12T10:00:00Z"));
        assertThat(CsesTime.parse("yesterday")).isNull();
        assertThat(CsesTime.parse(null)).isNull();
    }

    @Test
    void readsAGradedSubmission() throws Exception {
        var json = new ObjectMapper().readTree("""
            {"id": 9876, "task": {"id": "1640", "name": "Sum of Two Values"},
             "sender": {"id": 1, "username": "alice"}, "time": "2026-11-12T10:00:00Z",
             "language": {"name": "C++", "option": "C++17"}, "status": "READY", "pending": false,
             "result": "ACCEPTED", "test_progress": {"finished_tests": 20, "total_tests": 20},
             "tests": [{"number": 1, "verdict": "ACCEPTED", "time": 10}]}
            """);
        CsesClient.Graded g = CsesClient.graded(json);
        assertThat(g.id()).isEqualTo(9876);
        assertThat(g.taskId()).isEqualTo("1640");
        assertThat(g.time()).isEqualTo(Instant.parse("2026-11-12T10:00:00Z"));
        assertThat(g.pending()).isFalse();
        assertThat(g.result()).isEqualTo("ACCEPTED");
        assertThat(g.totalTests()).isEqualTo(20);
        assertThat(g.tests()).singleElement().satisfies(t -> assertThat(t.timeMs()).isEqualTo(10));
    }

    @Test
    void readsTheTaskPage() throws Exception {
        String page;
        try (var in = getClass().getResourceAsStream("/cses/task-1640.html")) {
            page = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        CsesStatements.Statement st = CsesStatements.parse("1640", page);
        assertThat(st.name()).isEqualTo("Sum of Two Values");
        assertThat(st.timeLimit()).isEqualTo("1.00 s");
        assertThat(st.memoryLimit()).isEqualTo("512 MB");
        assertThat(st.html()).contains("$$$1 \\le n \\le 2 \\cdot 10^5$$$", "<h1", "IMPOSSIBLE")
            .doesNotContain("<script", "math-inline");
        assertThat(st.examples()).singleElement().satisfies(e -> {
            assertThat(e.input()).isEqualTo("4 8\n2 7 5 1\n");
            assertThat(e.output()).isEqualTo("2 4\n");
        });
    }

    @Test
    void anythingButATaskPageIsNoStatement() {
        assertThat(CsesStatements.parse("1", "<html><title>CSES</title><body>Not found</body></html>")).isNull();
    }

    @Test
    void readsTheApisJsonWhateverItIsLabelledAndAnEmptyBody() {
        assertThat(CsesClient.json("{\"X-Auth-Token\": \"t\"}").path("X-Auth-Token").asText()).isEqualTo("t");
        assertThat(CsesClient.json("").isObject()).isTrue();
        assertThat(CsesClient.json(null).isObject()).isTrue();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> CsesClient.json("<html>"))
            .isInstanceOf(IllegalStateException.class);
    }
}
