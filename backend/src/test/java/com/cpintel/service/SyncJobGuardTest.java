package com.cpintel.service;

import com.cpintel.config.AppMetrics;
import com.cpintel.entity.PlatformAccount;
import com.cpintel.entity.SyncJob;
import com.cpintel.entity.User;
import com.cpintel.exception.ApiException;
import com.cpintel.integration.PlatformNormalizer;
import com.cpintel.integration.codeforces.CodeforcesClient;
import com.cpintel.repository.jpa.ContestSummaryRepository;
import com.cpintel.repository.jpa.PlatformAccountRepository;
import com.cpintel.repository.jpa.SyncJobRepository;
import com.cpintel.repository.jpa.UserRepository;
import com.cpintel.repository.mongo.CfSubmissionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.mongodb.core.MongoTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** One sync in flight per account. */
class SyncJobGuardTest {

    private static final Long USER = 5L;

    private SyncJobRepository jobs;
    private SyncService service;

    @BeforeEach
    void setUp() {
        jobs = mock(SyncJobRepository.class);
        UserRepository users = mock(UserRepository.class);
        PlatformAccountRepository accounts = mock(PlatformAccountRepository.class);
        when(users.findById(USER)).thenReturn(Optional.of(User.builder().userId(USER).build()));
        when(accounts.findByUserUserIdAndPlatform(USER, "CODEFORCES")).thenReturn(Optional.of(
            PlatformAccount.builder().accountId(9L).platform("CODEFORCES").handle("h").build()));
        when(jobs.saveAndFlush(any())).thenAnswer(call -> {
            SyncJob j = call.getArgument(0);
            if (j.getJobId() == null) j.setJobId(100L);
            return j;
        });
        service = new SyncService(mock(AppMetrics.class), users, accounts, jobs,
            mock(CfSubmissionRepository.class), mock(ContestSummaryRepository.class),
            mock(CodeforcesClient.class), mock(PlatformNormalizer.class),
            mock(ContestSyncService.class), mock(MongoTemplate.class),
            new org.springframework.beans.factory.support.StaticListableBeanFactory()
                .getBeanProvider(com.cpintel.analytics.AnalyticsService.class));
    }

    private SyncJob inFlight(Instant startedAt) {
        SyncJob j = SyncJob.builder().jobId(1L).platform("CODEFORCES").status("RUNNING")
            .startedAt(startedAt).build();
        when(jobs.findInFlight(USER, "CODEFORCES")).thenReturn(List.of(j));
        return j;
    }

    @Test
    @DisplayName("with nothing in flight, a job is queued")
    void queues() {
        when(jobs.findInFlight(USER, "CODEFORCES")).thenReturn(List.of());
        assertEquals(100L, service.createJob(USER, "CODEFORCES", "INCREMENTAL").jobId());
    }

    @Test
    @DisplayName("a second sync while one runs is refused, and the running one is left alone")
    void refusesSecond() {
        SyncJob running = inFlight(Instant.now().minus(Duration.ofMinutes(2)));

        ApiException e = assertThrows(ApiException.class,
            () -> service.createJob(USER, "CODEFORCES", "INCREMENTAL"));
        assertEquals(409, e.getStatus().value());
        assertEquals("RUNNING", running.getStatus());
    }

    @Test
    @DisplayName("a job silent for over an hour is closed as abandoned, and a new one starts")
    void closesAbandoned() {
        SyncJob dead = inFlight(Instant.now().minus(SyncService.ABANDONED_AFTER).minusSeconds(60));

        assertEquals(100L, service.createJob(USER, "CODEFORCES", "INCREMENTAL").jobId());
        assertEquals("FAILED", dead.getStatus());
        assertNotNull(dead.getCompletedAt());
    }

    @Test
    @DisplayName("two requests in the same instant: the one the index turns away is told why")
    void raceIsAConflict() {
        when(jobs.findInFlight(USER, "CODEFORCES")).thenReturn(List.of());
        doThrow(new DataIntegrityViolationException("uq")).when(jobs).saveAndFlush(any());

        ApiException e = assertThrows(ApiException.class,
            () -> service.createJob(USER, "CODEFORCES", "INCREMENTAL"));
        assertEquals(409, e.getStatus().value());
    }

    @Test
    @DisplayName("another user's sync job reads as not found")
    void otherUsersJobIsNotFound() {
        // Job 9001 belongs to someone else: the owner-bound lookup finds nothing for USER.
        when(jobs.findByJobIdAndUserUserId(9001L, USER)).thenReturn(Optional.empty());
        assertThrows(ApiException.class, () -> service.getSyncStatus(USER, 9001L));
        verify(jobs, never()).findById(9001L);
    }

    @Test
    @DisplayName("a handle another account already linked cannot be linked again")
    void sharedHandleRefused() {
        PlatformAccountRepository accounts = (PlatformAccountRepository)
            org.springframework.test.util.ReflectionTestUtils.getField(service, "platformAccountRepository");
        when(accounts.existsByUserUserIdAndPlatform(USER, "CODEFORCES")).thenReturn(false);
        when(accounts.existsByPlatformAndHandleIgnoreCaseAndUserUserIdNot("CODEFORCES", "Alice_CF", USER))
            .thenReturn(true);

        assertThrows(ApiException.class, () -> service.linkAccount(USER, "CODEFORCES", "Alice_CF"));
        verify(accounts, never()).save(any());
    }
}
