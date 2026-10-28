package com.cpintel.web;

import com.cpintel.admin.AdminAuditService;
import com.cpintel.admin.AdminOverviewService;
import com.cpintel.controller.AdminContestFileController;
import com.cpintel.controller.AdminGroupController;
import com.cpintel.controller.AdminEventController;
import com.cpintel.controller.AdminOverviewController;
import com.cpintel.events.EventAnalyticsService;
import com.cpintel.events.EventService;
import com.cpintel.events.ExamEventService;
import com.cpintel.events.ExamFlagService;
import com.cpintel.events.ExamLeaderboardService;
import com.cpintel.events.ExamMonitorService;
import com.cpintel.events.ExamPasswordService;
import com.cpintel.events.JudgeProblemService;
import com.cpintel.files.ContestFilePolicy;
import com.cpintel.repository.jpa.GroupMemberRepository;
import com.cpintel.groups.GroupService;
import com.cpintel.groups.RosterImportService;
import com.cpintel.service.AuditService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;

import org.springframework.http.MediaType;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The blanket rule over the rest of the console: an ordinary user reaches none of it, and an
 * anonymous caller is told to authenticate rather than told they are forbidden.
 *
 * <p>The distinction between 401 and 403 is worth pinning down rather than assuming. A client
 * that gets 403 has been told its credentials are fine and the answer is still no, so it will
 * not try to refresh; one that gets 401 knows to. Getting these the wrong way round turns an
 * expired token into a dead session instead of a silent renewal.
 */
@WebMvcTest(controllers = {
    AdminOverviewController.class,
    AdminGroupController.class,
    AdminContestFileController.class,
    AdminEventController.class,
})
class AdminConsoleAuthorizationTest extends AuthorizationTestBase {

    @MockBean private AdminOverviewService overview;
    @MockBean private AdminAuditService audit;
    @MockBean private GroupService groups;
    @MockBean private RosterImportService rosterImport;
    @MockBean private com.cpintel.groups.DomjudgePasswordImportService domjudgePasswords;
    @MockBean private ContestFilePolicy policy;
    @MockBean private AuditService auditService;
    @MockBean private EventAnalyticsService eventAnalytics;
    @MockBean private EventService events;
    @MockBean private ExamEventService examEvents;
    @MockBean private ExamMonitorService examMonitor;
    @MockBean private ExamFlagService examFlags;
    @MockBean private ExamLeaderboardService examLeaderboard;
    @MockBean private com.cpintel.events.EventCompletionService eventCompletion;
    @MockBean private com.cpintel.events.EventRejudgeService eventRejudge;
    @MockBean private ExamPasswordService examPasswords;
    @MockBean private JudgeProblemService judgeProblems;
    @MockBean private GroupMemberRepository members;
    @MockBean private com.cpintel.repository.jpa.GroupContestRepository groupContests;
    @MockBean private com.cpintel.classrooms.ClassroomService classroomService;

    private static final String[] ROUTES = {
        "/api/v1/admin/overview",
        "/api/v1/admin/audit",
        "/api/v1/admin/groups",
        "/api/v1/admin/contest-files",
        // Examinations: the roster of who is sitting one, and the session log of how they sat
        // it, are the most sensitive things the console holds.
        "/api/v1/admin/events",
    };

    @ParameterizedTest(name = "USER is refused {0}")
    @ValueSource(strings = {
        "/api/v1/admin/overview", "/api/v1/admin/audit", "/api/v1/admin/groups",
        "/api/v1/admin/contest-files", "/api/v1/admin/events"
    })
    @DisplayName("no part of the console is reachable by an ordinary user")
    void userRefusedEverywhere(String route) throws Exception {
        mvc.perform(get(route).with(asUser()))
            .andExpect(status().isForbidden());
    }

    @ParameterizedTest(name = "anonymous gets 401 on {0}")
    @ValueSource(strings = {
        "/api/v1/admin/overview", "/api/v1/admin/audit", "/api/v1/admin/groups",
        "/api/v1/admin/contest-files", "/api/v1/admin/events"
    })
    @DisplayName("an anonymous caller is asked to authenticate, not refused outright")
    void anonymousUnauthorizedEverywhere(String route) throws Exception {
        mvc.perform(get(route))
            .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("an admin is past the gate on every console route")
    void adminPastTheGate() throws Exception {
        for (String route : ROUTES) {
            mvc.perform(get(route).with(asAdmin()))
                .andExpect(status().is(not403()));
        }
    }

    @Test
    @DisplayName("a super admin is past the gate too — the hierarchy holds across controllers")
    void superAdminPastTheGate() throws Exception {
        for (String route : ROUTES) {
            mvc.perform(get(route).with(asSuperAdmin()))
                .andExpect(status().is(not403()));
        }
    }

    @Test
    @DisplayName("an ordinary user cannot read an examination's monitoring or session log")
    void userRefusedExamSurfaces() throws Exception {
        // The two routes that carry other people's conduct. Worth naming rather than trusting
        // the blanket rule above: these are the ones where a slip would be worst.
        mvc.perform(get("/api/v1/admin/events/1/monitor").with(asUser()))
            .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/admin/events/1/logs").with(asUser()))
            .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("an ordinary user cannot reach the bulk roster import")
    void userRefusedRosterImport() throws Exception {
        // Worth its own case rather than a row in the list above: this is the one console route
        // that creates accounts, so it is the one whose gate matters most, and it is a POST —
        // the parameterised GETs would not have covered it.
        mvc.perform(post("/api/v1/admin/groups/1/members/import")
                .with(asUser()).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"text\":\"email\\nasha@uni.edu\",\"dryRun\":true}"))
            .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("an admin is past the gate on the roster import")
    void adminPastTheGateOnRosterImport() throws Exception {
        // Past the gate only. Whether the import is then refused for creating accounts is
        // RosterImportService's decision and is covered by its own tests.
        mvc.perform(post("/api/v1/admin/groups/1/members/import")
                .with(asAdmin()).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"text\":\"email\\nasha@uni.edu\",\"dryRun\":true}"))
            .andExpect(status().is(not403()));
    }

    /**
     * These controllers are backed by mocks that return null, so a 200 is not on offer for all
     * of them and asserting one would be testing the mock. What matters here is only that the
     * request was not turned away at the gate.
     */
    private static org.hamcrest.Matcher<Integer> not403() {
        return org.hamcrest.Matchers.not(403);
    }

    private static final String RULE = "{\"enabled\":true,\"note\":\"x\"}";

    @Test
    @DisplayName("the deployment-wide file default is a superadmin's to move, not a classroom admin's")
    void fileDefaultIsSuperAdminOnly() throws Exception {
        mvc.perform(put("/api/v1/admin/contest-files/default").with(asAdmin())
                .contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":true}"))
            .andExpect(status().isForbidden());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .delete("/api/v1/admin/contest-files/default").with(asAdmin()))
            .andExpect(status().isForbidden());
        mvc.perform(put("/api/v1/admin/contest-files/default").with(asSuperAdmin())
                .contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":true}"))
            .andExpect(status().isOk());
    }

    @Test
    @DisplayName("a Codeforces contest's file rule is changed only by admins of the classrooms running it")
    void codeforcesRuleIsClassroomScoped() throws Exception {
        // This admin runs classroom 1; classroom 2 runs an event on Codeforces contest 1234.
        when(classroomService.managedIds(any())).thenReturn(java.util.List.of(1L));
        when(groupContests.findByPlatformAndExternalId("CODEFORCES", "1234")).thenReturn(java.util.List.of(
            com.cpintel.entity.GroupContest.builder().contestId(50L).classroomId(2L).name("B's round").build()));
        when(groupContests.findByPlatformAndExternalId("CODEFORCES", "1235")).thenReturn(java.util.List.of(
            com.cpintel.entity.GroupContest.builder().contestId(51L).classroomId(1L).name("A's round").build()));

        mvc.perform(put("/api/v1/admin/contest-files/CODEFORCES/1234").with(asAdmin())
                .contentType(MediaType.APPLICATION_JSON).content(RULE))
            .andExpect(status().isForbidden());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .delete("/api/v1/admin/contest-files/CODEFORCES/1234").with(asAdmin()))
            .andExpect(status().isForbidden());
        mvc.perform(put("/api/v1/admin/contest-files/CODEFORCES/1235").with(asAdmin())
                .contentType(MediaType.APPLICATION_JSON).content(RULE))
            .andExpect(status().isOk());
    }
}
