package com.cpintel.events;

import com.cpintel.compete.CfManagerApiTestAccess;
import com.cpintel.entity.GroupContest;
import com.cpintel.integration.codeforces.CfModels;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** What the readiness check says about a Codeforces contest's window. */
class CodeforcesReadinessServiceTest {

    private final Instant start = Instant.parse("2026-11-20T09:00:00Z");
    private final GroupContest paper = GroupContest.builder()
        .startsAt(start).endsAt(start.plusSeconds(7200)).build();

    @Test
    @DisplayName("a contest covering the paper is fine; one opening late or closing early is named")
    void window() {
        assertNull(CodeforcesReadinessService.windowProblem(paper, start, 7200L));
        assertNull(CodeforcesReadinessService.windowProblem(paper, start.minusSeconds(600), 9000L));
        assertTrue(CodeforcesReadinessService.windowProblem(paper, start.plusSeconds(600), 7200L)
            .contains("opens after"));
        assertTrue(CodeforcesReadinessService.windowProblem(paper, start, 3600L)
            .contains("closes before"));
        assertNull(CodeforcesReadinessService.windowProblem(paper, null, null), "unknown is not a problem");
    }

    @Test
    @DisplayName("a manager's list is narrowed to one candidate by handle, ignoring case")
    void byHandle() {
        CfModels.Submission mine = submission("Alice");
        CfModels.Submission theirs = submission("bob");
        assertTrue(CfManagerApiTestAccess.byHandle(mine, "alice"));
        assertFalse(CfManagerApiTestAccess.byHandle(theirs, "alice"));
        assertFalse(CfManagerApiTestAccess.byHandle(new CfModels.Submission(), "alice"));
    }

    private static CfModels.Submission submission(String handle) {
        CfModels.Submission s = new CfModels.Submission();
        CfModels.Submission.Author author = new CfModels.Submission.Author();
        CfModels.Submission.Member member = new CfModels.Submission.Member();
        member.setHandle(handle);
        author.setMembers(List.of(member));
        s.setAuthor(author);
        return s;
    }
}
