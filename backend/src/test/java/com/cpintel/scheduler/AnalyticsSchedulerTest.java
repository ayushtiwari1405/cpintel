package com.cpintel.scheduler;

import com.cpintel.analytics.AnalyticsService;
import com.cpintel.analytics.MaterializedViewRefresher;
import com.cpintel.analytics.UnifiedRatingService;
import com.cpintel.repository.jpa.TopicMasteryRepository;
import com.cpintel.repository.jpa.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.SliceImpl;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AnalyticsSchedulerTest {

    @Test
    @DisplayName("the nightly pass skips users the after-sync rebuild already did")
    void skipsRecentlyRebuilt() {
        UserRepository users = mock(UserRepository.class);
        TopicMasteryRepository mastery = mock(TopicMasteryRepository.class);
        AnalyticsService analytics = mock(AnalyticsService.class);
        when(users.findAllUserIds(any()))
            .thenReturn(new SliceImpl<>(List.of(1L, 2L, 3L), PageRequest.of(0, 200), false));
        when(mastery.findRebuiltSince(anyCollection(), any())).thenReturn(Set.of(2L));

        AnalyticsScheduler scheduler = new AnalyticsScheduler(users, mastery, analytics,
            mock(UnifiedRatingService.class), mock(MaterializedViewRefresher.class));

        Instant before = Instant.now();
        scheduler.refreshAllAnalytics();

        verify(analytics).updateTopicMasteryFromSubmissions(1L);
        verify(analytics).updateTopicMasteryFromSubmissions(3L);
        verify(analytics, never()).updateTopicMasteryFromSubmissions(2L);
        verify(mastery).findRebuiltSince(eq(List.of(1L, 2L, 3L)), argThat(since ->
            !since.isAfter(before.minus(AnalyticsScheduler.RECENTLY_REBUILT).plusSeconds(60))
                && !since.isBefore(before.minus(AnalyticsScheduler.RECENTLY_REBUILT))));
    }
}
