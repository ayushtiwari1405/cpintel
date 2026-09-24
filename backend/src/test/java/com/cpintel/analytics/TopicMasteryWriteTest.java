package com.cpintel.analytics;

import com.cpintel.entity.TopicMastery;
import com.cpintel.entity.mongo.CfSubmission;
import com.cpintel.exception.ApiException;
import com.cpintel.repository.jpa.ContestSummaryRepository;
import com.cpintel.repository.jpa.PlatformAccountRepository;
import com.cpintel.repository.jpa.TopicMasteryRepository;
import com.cpintel.repository.jpa.UserRepository;
import com.cpintel.repository.mongo.CfSubmissionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * How mastery counts are derived from a submission history and reach the database.
 *
 * <p>Three separate defects are pinned here, each of which silently produced wrong numbers
 * rather than an error.
 *
 * <p><b>The write shape.</b> Counts used to go through find-then-save, which raced: two
 * refreshes for one user would each find no row and each insert, and the loser came back to the
 * browser as a 500 on {@code uq_tm_user_topic}. Overlapping refreshes are ordinary - the
 * dashboard triggers one while the nightly pass is running - so the write has to tolerate them.
 *
 * <p><b>The counting unit.</b> Counts were incremented per submission, not per problem.
 *
 * <p><b>The practice date.</b> {@code last_practiced_at} was written as {@code now()} on every
 * refresh, which made every skill permanently fresh and disabled decay and revision entirely.
 */
class TopicMasteryWriteTest {

    private static final Long USER_ID = 13L;

    /** Well inside the DP-basics band, so the tags below attribute somewhere predictable. */
    private static final int DP_RATING = 1300;

    private UserRepository users;
    private TopicMasteryRepository topicMastery;
    private CfSubmissionRepository cfSubmissions;
    private AnalyticsService service;

    @BeforeEach
    void setUp() {
        users = mock(UserRepository.class);
        topicMastery = mock(TopicMasteryRepository.class);
        cfSubmissions = mock(CfSubmissionRepository.class);

        when(cfSubmissions.findByUserId(USER_ID)).thenReturn(List.of());

        service = new AnalyticsService(
            users,
            topicMastery,
            mock(ContestSummaryRepository.class),
            mock(PlatformAccountRepository.class),
            new PracticeHistory(cfSubmissions),
            mock(AnalyticsEngine.class),
            mock(UnifiedRatingService.class),
            mock(ContestAnalysisService.class),
            mock(RecommendationEngine.class));

        when(users.existsById(USER_ID)).thenReturn(true);
    }

    private CfSubmission cf(int contestId, String index, String verdict,
                            Integer rating, Instant at, String... tags) {
        CfSubmission s = new CfSubmission();
        s.setUserId(USER_ID);
        s.setContestId(contestId);
        s.setProblemIndex(index);
        s.setVerdict(verdict);
        s.setProblemRating(rating);
        s.setTags(List.of(tags));
        s.setSubmittedAt(at);
        return s;
    }

    @Test
    @DisplayName("counts are written with an upsert, never read-then-save")
    void writesThroughUpsert() {
        when(cfSubmissions.findByUserId(USER_ID)).thenReturn(List.of(
            cf(1000, "A", "OK", DP_RATING, Instant.now(), "dp")));

        service.updateTopicMasteryFromSubmissions(USER_ID);

        verify(topicMastery, atLeastOnce()).upsertCounts(
            eq(USER_ID), anyString(), anyString(), any(), any(),
            anyInt(), anyInt(), any());

        // The two halves of the old race. Either one returning means a refresh can still lose to
        // a concurrent one, so both are asserted rather than trusting the upsert call alone.
        verify(topicMastery, never()).findByUserUserIdAndTopic(any(), any());
        verify(topicMastery, never()).save(any());
    }

    @Test
    @DisplayName("one problem counts once however many times it was submitted")
    void countsDistinctProblemsNotSubmissions() {
        // Five wrong answers and then an accepted one, all on problem 1000A. The old code
        // recorded six attempts and one solve - an accuracy of 17% for a problem the user got
        // right - and a sixth resubmission of an accepted solution would have counted a second
        // solve on a single problem.
        Instant t = Instant.now();
        when(cfSubmissions.findByUserId(USER_ID)).thenReturn(List.of(
            cf(1000, "A", "WRONG_ANSWER", DP_RATING, t.minus(5, ChronoUnit.HOURS), "dp"),
            cf(1000, "A", "WRONG_ANSWER", DP_RATING, t.minus(4, ChronoUnit.HOURS), "dp"),
            cf(1000, "A", "WRONG_ANSWER", DP_RATING, t.minus(3, ChronoUnit.HOURS), "dp"),
            cf(1000, "A", "WRONG_ANSWER", DP_RATING, t.minus(2, ChronoUnit.HOURS), "dp"),
            cf(1000, "A", "OK", DP_RATING, t.minus(1, ChronoUnit.HOURS), "dp"),
            cf(1000, "A", "OK", DP_RATING, t, "dp")));

        service.updateTopicMasteryFromSubmissions(USER_ID);

        ArgumentCaptor<Integer> solved = ArgumentCaptor.forClass(Integer.class);
        ArgumentCaptor<Integer> attempted = ArgumentCaptor.forClass(Integer.class);
        verify(topicMastery, atLeastOnce()).upsertCounts(
            eq(USER_ID), anyString(), anyString(), any(), any(),
            solved.capture(), attempted.capture(), any());

        assertTrue(solved.getAllValues().stream().allMatch(v -> v == 1),
            "one problem solved once, got " + solved.getAllValues());
        assertTrue(attempted.getAllValues().stream().allMatch(v -> v == 1),
            "one problem attempted once, got " + attempted.getAllValues());
    }

    @Test
    @DisplayName("the practice date is the real submission date, not the time of the refresh")
    void recordsTheGenuinePracticeDate() {
        // This is the bug that killed decay and spaced repetition outright: the column was
        // passed Instant.now() on every refresh, so every skill always looked practised today,
        // decay was always zero, and the revision queue's decay filter never matched anything.
        Instant practised = Instant.now().minus(120, ChronoUnit.DAYS);
        when(cfSubmissions.findByUserId(USER_ID)).thenReturn(List.of(
            cf(1000, "A", "OK", DP_RATING, practised, "dp")));

        service.updateTopicMasteryFromSubmissions(USER_ID);

        ArgumentCaptor<Instant> at = ArgumentCaptor.forClass(Instant.class);
        verify(topicMastery, atLeastOnce()).upsertCounts(
            eq(USER_ID), anyString(), anyString(), any(), any(),
            anyInt(), anyInt(), at.capture());

        for (Instant written : at.getAllValues()) {
            assertEquals(practised, written,
                "last_practiced_at must be the submission date, not the refresh time");
        }
    }

    @Test
    @DisplayName("writes both a node row and a roll-up row")
    void writesBothGranularities() {
        when(cfSubmissions.findByUserId(USER_ID)).thenReturn(List.of(
            cf(1000, "A", "OK", DP_RATING, Instant.now(), "dp")));

        service.updateTopicMasteryFromSubmissions(USER_ID);

        ArgumentCaptor<String> scope = ArgumentCaptor.forClass(String.class);
        verify(topicMastery, atLeastOnce()).upsertCounts(
            eq(USER_ID), anyString(), scope.capture(), any(), any(),
            anyInt(), anyInt(), any());

        assertTrue(scope.getAllValues().contains(TopicMastery.Scope.NODE),
            "expected at least one NODE row");
        assertTrue(scope.getAllValues().contains(TopicMastery.Scope.TOPIC),
            "expected at least one TOPIC roll-up row");
    }

    @Test
    @DisplayName("an account that does not exist is refused before anything is written")
    void unknownUserWritesNothing() {
        when(users.existsById(USER_ID)).thenReturn(false);
        when(cfSubmissions.findByUserId(USER_ID)).thenReturn(List.of(
            cf(1000, "A", "OK", DP_RATING, Instant.now(), "dp")));

        assertThrows(ApiException.class, () -> service.updateTopicMasteryFromSubmissions(USER_ID));
        verifyNoInteractions(topicMastery);
    }

    @Test
    @DisplayName("a history with no recognised tags writes nothing rather than empty rows")
    void noRecognisedTagsWritesNothing() {
        when(cfSubmissions.findByUserId(USER_ID)).thenReturn(List.of(
            cf(1000, "A", "OK", DP_RATING, Instant.now(), "not-a-real-tag")));

        service.updateTopicMasteryFromSubmissions(USER_ID);

        verifyNoInteractions(topicMastery);
    }

    @Test
    @DisplayName("stale rows are cleared so a retired skill cannot linger at its last score")
    void clearsRowsWithNoRemainingEvidence() {
        when(cfSubmissions.findByUserId(USER_ID)).thenReturn(List.of(
            cf(1000, "A", "OK", DP_RATING, Instant.now(), "dp")));

        service.updateTopicMasteryFromSubmissions(USER_ID);

        verify(topicMastery).deleteStale(eq(USER_ID), argThat(keep -> !keep.isEmpty()));
    }
}
