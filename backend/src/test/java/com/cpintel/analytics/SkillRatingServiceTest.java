package com.cpintel.analytics;

import com.cpintel.entity.ContestSummary;
import com.cpintel.entity.PlacementResult;
import com.cpintel.entity.User;
import com.cpintel.entity.mongo.CfSubmission;
import com.cpintel.integration.codeforces.CfModels;
import com.cpintel.integration.codeforces.CfProblemsetClient;
import com.cpintel.repository.jpa.ContestSummaryRepository;
import com.cpintel.repository.jpa.PlacementResultRepository;
import com.cpintel.repository.mongo.CfSubmissionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class SkillRatingServiceTest {

    private static final Long USER = 7L;

    private ContestSummaryRepository contests;
    private CfSubmissionRepository submissions;
    private CfProblemsetClient problemset;
    private PlacementResultRepository placements;
    private SkillRatingService service;

    private final List<ContestSummary> rounds = new ArrayList<>();
    private final List<CfModels.Submission.Problem> problems = new ArrayList<>();
    private final List<CfSubmission> subs = new ArrayList<>();

    @BeforeEach
    void setUp() {
        contests = mock(ContestSummaryRepository.class);
        submissions = mock(CfSubmissionRepository.class);
        problemset = mock(CfProblemsetClient.class);
        placements = mock(PlacementResultRepository.class);
        when(contests.findByUserUserIdAndPlatform(USER, "CODEFORCES")).thenReturn(rounds);
        when(problemset.getAllProblems()).thenReturn(problems);
        when(submissions.findByUserId(USER)).thenReturn(subs);
        when(placements.findFirstByUserIdOrderByCreatedAtDesc(any())).thenReturn(Optional.empty());
        service = new SkillRatingService(contests, submissions, problemset, placements,
            new ObjectMapper());
    }

    private User user(String basis) {
        return User.builder().userId(USER).skillBasis(basis).build();
    }

    private Instant daysAgo(int days) {
        return Instant.now().minus(Duration.ofDays(days));
    }

    private void round(int contestId, int before, int after, Instant at) {
        rounds.add(ContestSummary.builder().contestCfId(String.valueOf(contestId))
            .ratingBefore(before).ratingAfter(after).contestDate(at).platform("CODEFORCES").build());
    }

    private void problem(int contestId, String index, int rating, String... tags) {
        CfModels.Submission.Problem p = new CfModels.Submission.Problem();
        p.setContestId(contestId);
        p.setIndex(index);
        p.setRating(rating);
        p.setTags(List.of(tags));
        problems.add(p);
    }

    private void solvedLive(int contestId, String index) {
        CfSubmission s = new CfSubmission();
        s.setContestId(contestId);
        s.setProblemIndex(index);
        s.setVerdict("OK");
        s.setParticipantType("CONTESTANT");
        subs.add(s);
    }

    private void placed(int overall, String sectionsJson) {
        when(placements.findFirstByUserIdOrderByCreatedAtDesc(USER)).thenReturn(Optional.of(
            PlacementResult.builder().resultId(1L).userId(USER).overallRating(overall)
                .sections(sectionsJson).build()));
    }

    @Test
    @DisplayName("a new account's shown rating is corrected for the 1400 it starts at underneath")
    void newAccountCorrection() {
        assertEquals(1400, SkillRatingService.unpaid(true, 0));
        assertEquals(900, SkillRatingService.unpaid(true, 1));
        assertEquals(50, SkillRatingService.unpaid(true, 5));
        assertEquals(0, SkillRatingService.unpaid(true, 6));
        assertEquals(0, SkillRatingService.unpaid(false, 0));

        // Shown 0 -> 600 -> 1000: underneath, 1400 -> 1500 -> 1550.
        round(1, 0, 600, daysAgo(30));
        round(2, 600, 1000, daysAgo(20));
        var rated = service.ratedRounds(USER);
        assertEquals(1400, rated.get(0).ratingBefore());
        assertEquals(1500, rated.get(1).ratingBefore());
        assertEquals(1550, rated.get(1).ratingAfter());
    }

    @Test
    @DisplayName("with no rounds and no gauntlet there is no rating, and coverage is shown")
    void nothingToRateFrom() {
        var profile = service.profile(user("AUTO"));
        assertNull(profile.basis());
        assertTrue(profile.nodes().isEmpty());
    }

    @Test
    @DisplayName("choosing the gauntlet ignores rated rounds, even good ones")
    void gauntletIgnoresRounds() {
        round(1, 1500, 1600, daysAgo(10));
        placed(1200, "[{\"section\":\"dp\",\"title\":\"DP\",\"tiersPassed\":1,\"rating\":1200}]");

        var profile = service.profile(user("GAUNTLET"));

        assertEquals(SkillRatingService.GAUNTLET, profile.basis());
        assertEquals(1200, profile.nodes().get("dp-basics").rating(), 1e-9);
        assertEquals(SkillRatingService.GAUNTLET_SD, profile.nodes().get("dp-basics").sd());
    }

    @Test
    @DisplayName("choosing the gauntlet without having taken it gives coverage, not Codeforces")
    void gauntletNeverFallsBackToCodeforces() {
        round(1, 1500, 1600, daysAgo(10));
        assertNull(service.profile(user("GAUNTLET")).basis());
    }

    @Test
    @DisplayName("on AUTO, rated rounds come first, then the gauntlet")
    void autoOrder() {
        placed(1200, "[]");
        assertEquals(SkillRatingService.GAUNTLET, service.summary(user("AUTO")).basis());

        round(1, 1500, 1600, daysAgo(10));
        assertEquals(SkillRatingService.CODEFORCES, service.summary(user("AUTO")).basis());
        assertEquals(1600, service.summary(user("AUTO")).rating());
    }

    @Test
    @DisplayName("rounds move a skill: solving its problems in the round lifts it above the rating")
    void roundsMoveSkills() {
        for (int c = 1; c <= 8; c++) {
            round(c, 1300, 1300, daysAgo(100 - c));
            problem(c, "A", 1400, "dp");
            problem(c, "B", 1400, "greedy");
            solvedLive(c, "A");
        }
        var profile = service.profile(user("AUTO"));

        assertEquals(SkillRatingService.CODEFORCES, profile.basis());
        var dp = profile.nodes().get("dp-basics");
        assertTrue(dp.rating() > 1300, "dp " + dp.rating());
        // Eight rounds, the first on the old system's placeholder rating: seven are evidence.
        assertEquals(7, dp.problems());
        assertTrue(profile.topics().get("Greedy").rating() < 1300);
        // A node no round has touched sits at the rating, as unsure as the prior.
        var untouched = profile.nodes().get("suffix-automaton");
        assertEquals(0, untouched.problems());
        assertEquals(1300, untouched.rating(), 1e-9);
        assertEquals(SkillRating.PRIOR_SD, untouched.sd(), 1e-9);
    }

    @Test
    @DisplayName("an upsolve the day after is not a solve in the round")
    void upsolveIsNotInRound() {
        round(1, 0, 400, daysAgo(5));
        problem(1, "A", 1400, "dp");
        CfSubmission practice = new CfSubmission();
        practice.setContestId(1);
        practice.setProblemIndex("A");
        practice.setVerdict("OK");
        practice.setParticipantType("PRACTICE");
        subs.add(practice);

        // A new account: shown 0 -> 400 is 1400 -> 1300 underneath. The DP problem counts as
        // unsolved in the round, so DP sits below the 1300 rating rather than above it.
        var dp = service.profile(user("AUTO")).nodes().get("dp-basics");
        assertEquals(1, dp.problems());
        assertTrue(dp.rating() < 1300, "dp " + dp.rating());
    }
}
