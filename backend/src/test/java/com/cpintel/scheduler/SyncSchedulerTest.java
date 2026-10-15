package com.cpintel.scheduler;

import com.cpintel.repository.jpa.PlatformAccountRepository;
import com.cpintel.repository.jpa.RefreshTokenRepository;
import com.cpintel.service.SyncService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class SyncSchedulerTest {

    /** The job was a log line and nothing else for as long as it existed. */
    @Test
    @DisplayName("the token clean-up deletes what has expired, as of now")
    void cleanupDeletesExpiredTokens() {
        RefreshTokenRepository tokens = mock(RefreshTokenRepository.class);
        when(tokens.deleteExpiredTokens(any())).thenReturn(3);
        SyncScheduler scheduler = new SyncScheduler(
            mock(PlatformAccountRepository.class), mock(SyncService.class), tokens);

        Instant before = Instant.now();
        scheduler.cleanExpiredTokens();

        verify(tokens).deleteExpiredTokens(argThat(cutoff ->
            !cutoff.isBefore(before) && cutoff.isBefore(before.plus(Duration.ofMinutes(1)))));
    }
}
