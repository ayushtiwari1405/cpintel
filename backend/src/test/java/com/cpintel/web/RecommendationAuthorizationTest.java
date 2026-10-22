package com.cpintel.web;

import com.cpintel.analytics.Plan;
import com.cpintel.service.RecommendationService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;

import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Practice plans are personal: every route needs a signed-in user and answers only about that
 * user. No route takes a user id, so the principal is the only id that can reach the service.
 */
@WebMvcTest(controllers = com.cpintel.controller.RecommendationController.class)
class RecommendationAuthorizationTest extends AuthorizationTestBase {

    @MockBean
    private RecommendationService recommendations;

    private static RecommendationService.RecommendationPayload empty(String type) {
        return new RecommendationService.RecommendationPayload(type, List.of(), null, null,
            Plan.Summary.of(Plan.NONE, List.of()), new RecommendationService.Progress(0, 0));
    }

    @Test
    @DisplayName("every plan route refuses an anonymous caller")
    void anonymousRefused() throws Exception {
        for (String path : List.of("/api/v1/recommendations/daily", "/api/v1/recommendations/weekly",
                "/api/v1/recommendations/contest-prep", "/api/v1/recommendations/revision")) {
            mvc.perform(get(path)).andExpect(status().isUnauthorized());
        }
        mvc.perform(post("/api/v1/recommendations/daily/regenerate")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/recommendations/revision/7/done")).andExpect(status().isUnauthorized());
        verifyNoInteractions(recommendations);
    }

    @Test
    @DisplayName("each plan is read for the signed-in user and nobody else")
    void readsOwnPlans() throws Exception {
        when(recommendations.getDaily(USER_ID)).thenReturn(empty("DAILY"));
        when(recommendations.getWeekly(USER_ID)).thenReturn(empty("WEEKLY"));
        when(recommendations.getContestPrep(USER_ID)).thenReturn(empty("CONTEST_PREP"));

        mvc.perform(get("/api/v1/recommendations/daily").with(asUser()))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.type").value("DAILY"));
        mvc.perform(get("/api/v1/recommendations/weekly").with(asUser())).andExpect(status().isOk());
        mvc.perform(get("/api/v1/recommendations/contest-prep").with(asUser()))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.progress.total").value(0));

        verify(recommendations).getDaily(USER_ID);
        verify(recommendations).getWeekly(USER_ID);
        verify(recommendations).getContestPrep(USER_ID);
        verifyNoMoreInteractions(recommendations);
    }

    @Test
    @DisplayName("a user id in the query string is ignored")
    void queryUserIdIgnored() throws Exception {
        when(recommendations.getDaily(anyLong())).thenReturn(empty("DAILY"));
        mvc.perform(get("/api/v1/recommendations/daily?userId=7").with(asUser())).andExpect(status().isOk());
        verify(recommendations).getDaily(USER_ID);
        verify(recommendations, never()).getDaily(7L);
    }

    @Test
    @DisplayName("regenerate names its plan type; an unknown type is a 400")
    void regenerateType() throws Exception {
        when(recommendations.regenerate(eq(USER_ID), any())).thenReturn(empty("WEEKLY"));
        mvc.perform(post("/api/v1/recommendations/weekly/regenerate").with(asUser())).andExpect(status().isOk());
        verify(recommendations).regenerate(USER_ID, RecommendationService.SheetType.WEEKLY);

        mvc.perform(post("/api/v1/recommendations/everything/regenerate").with(asUser()))
            .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("the revision horizon is passed through, and a non-number is a 400")
    void revisionHorizon() throws Exception {
        when(recommendations.getRevisionQueue(USER_ID, 30)).thenReturn(List.of());
        mvc.perform(get("/api/v1/recommendations/revision?horizonDays=30").with(asUser()))
            .andExpect(status().isOk());
        verify(recommendations).getRevisionQueue(USER_ID, 30);

        mvc.perform(get("/api/v1/recommendations/revision?horizonDays=soon").with(asUser()))
            .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("marking a revision done passes the caller's own id with it")
    void markDoneOwnId() throws Exception {
        mvc.perform(post("/api/v1/recommendations/revision/9/done").with(asUser())).andExpect(status().isOk());
        verify(recommendations).markRevisionDone(USER_ID, 9L);
    }
}
