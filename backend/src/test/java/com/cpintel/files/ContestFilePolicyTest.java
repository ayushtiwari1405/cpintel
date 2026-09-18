package com.cpintel.files;

import com.cpintel.entity.mongo.ContestFileRule;
import com.cpintel.exception.ApiException;
import com.cpintel.repository.mongo.ContestFileRuleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Which of the three layers answers, and what happens when the bottom one is unreachable.
 *
 * The default ships enabled, so the first test here is the state the product is actually in
 * today: no rules anywhere, every contest allowing personal files.
 */
class ContestFilePolicyTest {

    private static final String CF = "CODEFORCES";

    private ContestFileRuleRepository repo;
    private ContestFilePolicy policy;

    @BeforeEach
    void setUp() {
        repo = mock(ContestFileRuleRepository.class);
        policy = new ContestFilePolicy(repo);
        ReflectionTestUtils.setField(policy, "configDefault", true);
        when(repo.findByPlatformAndContestId(anyString(), any())).thenReturn(Optional.empty());
    }

    private ContestFileRule rule(String platform, String contestId, boolean enabled) {
        return ContestFileRule.builder()
            .platform(platform).contestId(contestId).enabled(enabled)
            .updatedAt(Instant.now()).build();
    }

    @Test
    @DisplayName("with no rules at all, every contest allows personal files")
    void defaultsToEnabled() {
        assertTrue(policy.enabledFor(CF, "2259"));
        assertDoesNotThrow(() -> policy.require(CF, "2259"));
    }

    @Test
    @DisplayName("a rule for one contest closes that contest and no other")
    void contestRuleIsSpecific() {
        when(repo.findByPlatformAndContestId(CF, "2259"))
            .thenReturn(Optional.of(rule(CF, "2259", false)));

        assertFalse(policy.enabledFor(CF, "2259"));
        assertTrue(policy.enabledFor(CF, "2260"));

        ApiException e = assertThrows(ApiException.class, () -> policy.require(CF, "2259"));
        assertTrue(e.getMessage().toLowerCase().contains("contest"),
            "the message should point at the contest, not at the feature");
    }

    @Test
    @DisplayName("a deployment-wide rule overrides the configured default")
    void globalRuleOverridesConfig() {
        when(repo.findByPlatformAndContestId(ContestFileRule.GLOBAL, null))
            .thenReturn(Optional.of(rule(ContestFileRule.GLOBAL, null, false)));

        assertFalse(policy.defaultEnabled());
        assertFalse(policy.enabledFor(CF, "2259"));
    }

    @Test
    @DisplayName("a contest rule beats the deployment-wide one, in both directions")
    void contestRuleBeatsGlobal() {
        when(repo.findByPlatformAndContestId(ContestFileRule.GLOBAL, null))
            .thenReturn(Optional.of(rule(ContestFileRule.GLOBAL, null, false)));
        when(repo.findByPlatformAndContestId(CF, "2259"))
            .thenReturn(Optional.of(rule(CF, "2259", true)));

        assertTrue(policy.enabledFor(CF, "2259"));
        assertFalse(policy.enabledFor(CF, "9999"));
    }

    @Test
    @DisplayName("an unreachable database falls back to the configured default")
    void databaseFailureFallsBack() {
        when(repo.findByPlatformAndContestId(anyString(), any()))
            .thenThrow(new RuntimeException("mongo down"));

        // Better a panel that should have been hidden than a contest page that will not load.
        assertTrue(policy.enabledFor(CF, "2259"));
    }

    @Test
    @DisplayName("the overview lists contest rules and reports whether the default is still config's")
    void overviewSeparatesTheGlobalRow() {
        when(repo.findAllByOrderByUpdatedAtDesc()).thenReturn(List.of(
            rule(ContestFileRule.GLOBAL, null, false),
            rule(CF, "2259", true)));
        when(repo.findByPlatformAndContestId(ContestFileRule.GLOBAL, null))
            .thenReturn(Optional.of(rule(ContestFileRule.GLOBAL, null, false)));

        FilesDto.PolicyOverview overview = policy.overview();
        assertFalse(overview.defaultEnabled());
        assertFalse(overview.defaultFromConfig());
        assertEquals(1, overview.rules().size());
        assertEquals("2259", overview.rules().get(0).contestId());
    }
}
