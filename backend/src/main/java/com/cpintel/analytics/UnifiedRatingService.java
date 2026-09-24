package com.cpintel.analytics;

import com.cpintel.entity.PlatformAccount;
import com.cpintel.entity.UnifiedScore;
import com.cpintel.repository.jpa.PlatformAccountRepository;
import com.cpintel.repository.jpa.UnifiedScoreRepository;
import com.cpintel.repository.jpa.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * The unified score: a user's standing as one number on a 0-1000 scale. Built from Codeforces
 * alone since LeetCode and CodeChef were dropped. Replaces the pkg_unified_rating PL/SQL
 * package.
 *
 * The Oracle version had compute_unified_score doing an UPDATE inside a function,
 * which meant reading a score silently rewrote it, and callers had to invoke a
 * procedure and then re-SELECT the row to find out what it computed. That split is
 * gone: {@link #computeBreakdown} calculates and returns, {@link #updateScoreForUser}
 * persists. Reading no longer writes.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class UnifiedRatingService {

    private final UnifiedScoreRepository unifiedScoreRepository;
    private final PlatformAccountRepository platformAccountRepository;
    private final UserRepository userRepository;

    /**
     * Self-reference used by {@link #updateAllScores()}. Calling updateScoreForUser
     * directly from inside this class would bypass the transaction proxy and run the
     * whole batch outside a transaction; going through the proxy gives each user its
     * own.
     */
    private final ObjectProvider<UnifiedRatingService> self;

    /** The Codeforces rating on the 0-1000 scale, and the unified score built from it. */
    public record Breakdown(double cfScore, double unifiedScore) {
        public static Breakdown empty() { return new Breakdown(0, 0); }
    }

    /** Normalize one platform rating onto the shared 0-1000 scale. */
    public double normalizeRating(String platform, Integer rating) {
        return ScoringFormulas.normalizeRating(platform, rating);
    }

    /**
     * Compute a user's unified score without writing anything.
     *
     * <p>Codeforces is the only platform now, so the unified score is its rating on the
     * 0-1000 scale — the blend with LeetCode and CodeChef went with them. Kept as its own
     * number so a second judge can be blended back in without every screen changing.
     */
    @Transactional(readOnly = true)
    public Breakdown computeBreakdown(Long userId) {
        UnifiedScore score = unifiedScoreRepository.findByUserUserId(userId).orElse(null);
        if (score == null) {
            log.debug("No unified_scores row for user {}; returning zero", userId);
            return Breakdown.empty();
        }

        Integer cfRating = activeRating(userId, "CODEFORCES");
        double cfNorm = cfRating == null
            ? 0.0 : ScoringFormulas.normalizeRating("CODEFORCES", cfRating);
        return new Breakdown(cfNorm, cfNorm);
    }

    /**
     * Recompute and persist a user's unified score and its per-platform components.
     *
     * @return the score that was written, or 0 if the user has no linked platforms.
     */
    @Transactional
    public double updateScoreForUser(Long userId) {
        UnifiedScore score = unifiedScoreRepository.findByUserUserId(userId).orElse(null);
        if (score == null) {
            log.debug("No unified_scores row for user {}; nothing to update", userId);
            return 0.0;
        }

        Breakdown breakdown = computeBreakdown(userId);
        score.setCfScore(breakdown.cfScore());
        score.setUnifiedScore(breakdown.unifiedScore());
        score.setComputedAt(Instant.now());
        unifiedScoreRepository.save(score);

        return breakdown.unifiedScore();
    }

    /**
     * Recompute every active user's score. Each user commits in its own transaction so
     * one bad row cannot roll back the whole nightly batch.
     */
    public void updateAllScores() {
        List<Long> userIds = userRepository.findActiveUserIds();
        int updated = 0, failed = 0;

        UnifiedRatingService proxy = self.getObject();
        for (Long userId : userIds) {
            try {
                proxy.updateScoreForUser(userId);
                updated++;
            } catch (Exception e) {
                failed++;
                log.warn("Unified score update failed for user {}: {}", userId, e.getMessage());
            }
        }

        log.info("Unified score batch complete. updated={} failed={}", updated, failed);
    }

    /** Current rating of a linked, active account, or null if it is absent or inactive. */
    private Integer activeRating(Long userId, String platform) {
        return platformAccountRepository.findByUserUserIdAndPlatform(userId, platform)
            .filter(pa -> Boolean.TRUE.equals(pa.getIsActive()))
            .map(PlatformAccount::getCurrentRating)
            .orElse(null);
    }
}
