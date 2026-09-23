package com.cpintel.groups;

import com.cpintel.entity.GroupContest;
import com.cpintel.events.EventService;
import com.cpintel.events.ExamAccessService;
import com.cpintel.exception.ApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * When a submission is refused, and — more importantly — when it is not.
 *
 * <p>The failure mode worth testing here is not the refusal. It is the false refusal: a gate
 * that is too eager blocks somebody mid-round, with a clock running, over something nobody
 * asked for. Most of these are about the paths that must stay open.
 *
 * <p>The widest of those is the whole of the contest half of the product. Only examinations are
 * proctored, so an ordinary round must reach the judge without this class consulting anything
 * at all — which is asserted here rather than left to be true by accident.
 */
class ProctoringGateTest {

    private static final Long USER = 42L;
    private static final Long CONTEST = 9L;
    private static final String CID = "nwerc18";

    private GroupService groups;
    private EventService events;
    private ContestMonitorRegistry monitors;
    private ExamAccessService access;
    private ProctoringGate gate;

    @BeforeEach
    void setUp() {
        groups = mock(GroupService.class);
        events = mock(EventService.class);
        monitors = mock(ContestMonitorRegistry.class);
        access = mock(ExamAccessService.class);
        gate = new ProctoringGate(groups, events, monitors, access);

        when(events.require(CONTEST)).thenReturn(
            GroupContest.builder().contestId(CONTEST).kind("EXAM").build());
    }

    private GroupsDto.ContestSummary event(String kind, boolean lockdownRequired) {
        return new GroupsDto.ContestSummary(
            CONTEST, kind, 1L, "Class", "DOMJUDGE", CID, "NWERC 2018", null,
            Instant.now().minusSeconds(60), Instant.now().plusSeconds(3600),
            lockdownRequired, 10, "LIVE", "ACTIVE", null, null);
    }

    @Nested
    @DisplayName("Paths that must stay open")
    class Open {

        @Test
        @DisplayName("a round no team is sitting is never gated")
        void ungroupedPassesThrough() {
            when(groups.activeFor(USER, "DOMJUDGE", CID)).thenReturn(null);

            assertDoesNotThrow(() -> gate.requireMonitored(USER, "DOMJUDGE", CID));

            verifyNoInteractions(monitors, access);
        }

        /**
         * The rule the whole contest half of the product rests on.
         *
         * A contest is never proctored — not by default, but at all. If this ever starts
         * failing, every practice round in the deployment has quietly become an examination.
         */
        @Test
        @DisplayName("a contest is never gated, even if its row says lockdown is required")
        void contestsAreNeverGated() {
            when(groups.activeFor(USER, "DOMJUDGE", CID)).thenReturn(event("CONTEST", true));

            assertDoesNotThrow(() -> gate.requireMonitored(USER, "DOMJUDGE", CID));

            // Not merely allowed: nothing about monitoring or examination access is consulted,
            // so no future change to either can reach a contest by accident.
            verifyNoInteractions(monitors, access);
        }

        @Test
        @DisplayName("an unlocked examination whose admin did not require monitoring is allowed")
        void examWithoutLockdownPassesThrough() {
            when(groups.activeFor(USER, "DOMJUDGE", CID)).thenReturn(event("EXAM", false));

            assertDoesNotThrow(() -> gate.requireMonitored(USER, "DOMJUDGE", CID));

            // The password check still ran — that is not the part an admin turned off.
            verify(access).requireUnlocked(any(), eq(USER));
            verifyNoInteractions(monitors);
        }

        @Test
        @DisplayName("a monitored examination with a live heartbeat is allowed")
        void monitoredAndBeatingPassesThrough() {
            when(groups.activeFor(USER, "DOMJUDGE", CID)).thenReturn(event("EXAM", true));
            when(monitors.isMonitored(USER, CONTEST)).thenReturn(true);

            assertDoesNotThrow(() -> gate.requireMonitored(USER, "DOMJUDGE", CID));
        }
    }

    @Nested
    @DisplayName("Refusals")
    class Refusals {

        @Test
        @DisplayName("an examination nobody unlocked is refused before monitoring is considered")
        void lockedExamIsRefused() {
            when(groups.activeFor(USER, "DOMJUDGE", CID)).thenReturn(event("EXAM", true));
            doThrow(ApiException.forbidden("not unlocked"))
                .when(access).requireUnlocked(any(), eq(USER));

            assertThrows(ApiException.class,
                () -> gate.requireMonitored(USER, "DOMJUDGE", CID));

            // Order matters for the message the candidate gets: somebody who never typed their
            // password has a different problem from somebody whose monitor stopped, and being
            // told about the monitor would send them looking for the wrong fix.
            verifyNoInteractions(monitors);
        }

        @Test
        @DisplayName("a monitored examination with nothing reporting is refused")
        void monitoredAndSilentIsRefused() {
            when(groups.activeFor(USER, "DOMJUDGE", CID)).thenReturn(event("EXAM", true));
            when(monitors.isMonitored(USER, CONTEST)).thenReturn(false);

            ApiException e = assertThrows(ApiException.class,
                () -> gate.requireMonitored(USER, "DOMJUDGE", CID));

            // The message has to send them back to the examination window, not to an admin. A
            // bare "forbidden" reads as a permissions problem and costs a candidate minutes.
            String message = e.getMessage().toLowerCase();
            assertTrue(message.contains("window") || message.contains("monitor"),
                "the refusal should say what to do about it, not merely that it happened");
        }

        @Test
        @DisplayName("the gate is judge-agnostic, so a Codeforces examination is gated the same")
        void appliesToAnyJudge() {
            when(groups.activeFor(USER, "CODEFORCES", "2259")).thenReturn(event("EXAM", true));
            when(monitors.isMonitored(USER, CONTEST)).thenReturn(false);

            // Lives in the router rather than in a provider precisely so this holds: a second
            // copy inside the DOMjudge provider would have left the identical Codeforces round
            // open.
            assertThrows(ApiException.class,
                () -> gate.requireMonitored(USER, "CODEFORCES", "2259"));
        }
    }
}
