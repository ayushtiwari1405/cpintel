package com.cpintel.service;

import com.cpintel.entity.ContestSummary;
import com.cpintel.entity.User;
import com.cpintel.entity.mongo.CfSubmission;
import com.cpintel.exception.ApiException;
import com.cpintel.integration.codeforces.CfModels;
import com.cpintel.integration.codeforces.CodeforcesClient;
import com.cpintel.repository.jpa.ContestSummaryRepository;
import com.cpintel.repository.jpa.PlatformAccountRepository;
import com.cpintel.repository.jpa.UserRepository;
import com.cpintel.repository.mongo.CfSubmissionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class ContestSyncService {

    private final UserRepository userRepository;
    private final ContestSummaryRepository contestSummaryRepository;
    private final PlatformAccountRepository platformAccountRepository;
    private final CfSubmissionRepository cfSubmissionRepository;
    private final CodeforcesClient cfClient;
    private final com.cpintel.integration.codeforces.CfProblemsetClient problemset;

    /**
     * Whether a submission was sent while its author was competing in the round. Rows stored
     * before the participant type was kept fall back to "accepted in the few hours before the
     * rating update", until a sync fills the type in.
     */
    public static boolean inRound(CfSubmission s, Instant ratedAt) {
        if (s.getParticipantType() != null) return "CONTESTANT".equals(s.getParticipantType());
        return ratedAt != null && s.getSubmittedAt() != null
            && !s.getSubmittedAt().isAfter(ratedAt)
            && s.getSubmittedAt().isAfter(ratedAt.minus(java.time.Duration.ofHours(5)));
    }

    @Transactional
    public int syncCfContests(Long userId) {
        var account = platformAccountRepository
            .findByUserUserIdAndPlatform(userId, "CODEFORCES")
            .orElseThrow(() -> ApiException.notFound("Codeforces account not linked"));

        String handle = account.getHandle();
        log.info("Syncing CF contests for user {} handle {}", userId, handle);

        // Fetch rating history from CF API
        var ratingResp = cfClient.getRatingHistory(handle);
        if (ratingResp == null || ratingResp.getResult() == null) {
            log.warn("No rating history for {}", handle);
            return 0;
        }

        // Defense in depth: always rebuild this platform's contest rows from
        // scratch on every full sync rather than trusting "skip if exists" by
        // contest_cf_id alone. If the linked handle ever changed since the last
        // sync, stale rows from a previous handle would otherwise survive
        // forever because their contest_cf_id values never match the new
        // handle's contests. Wiping first guarantees the table only ever
        // reflects the currently linked handle.
        contestSummaryRepository.deleteByUserUserIdAndPlatform(userId, "CODEFORCES");
        log.info("Cleared existing CODEFORCES contest rows for user {} before rebuild", userId);

        User user = userRepository.findById(userId)
            .orElseThrow(() -> ApiException.notFound("User not found"));

        // Get CF submissions grouped by contestId to compute per-contest stats
        List<CfSubmission> allSubs = cfSubmissionRepository.findByUserId(userId);
        Map<Integer, List<CfSubmission>> subsByContest = allSubs.stream()
            .filter(s -> s.getContestId() != null)
            .collect(Collectors.groupingBy(CfSubmission::getContestId));

        Map<Integer, Integer> problemsIn = new java.util.HashMap<>();
        for (CfModels.Submission.Problem p : problemset.getAllProblems()) {
            if (p.getContestId() != null) problemsIn.merge(p.getContestId(), 1, Integer::sum);
        }

        List<CfModels.RatingChange> ratings = ratingResp.getResult();
        int saved = 0;

        for (CfModels.RatingChange rc : ratings) {
            // Skip if already exists
            String cfId = String.valueOf(rc.getContestId());
            if (contestSummaryRepository.findByUserUserIdAndContestCfId(userId, cfId).isPresent()) {
                continue;
            }

            // Compute stats from submissions in this contest
            List<CfSubmission> contestSubs = subsByContest.getOrDefault(rc.getContestId(), List.of());

            // Solved in the round, not afterwards: an upsolve the next day is not a result.
            Instant ratedAt = rc.getRatingUpdateTimeSeconds() == null ? null
                : Instant.ofEpochSecond(rc.getRatingUpdateTimeSeconds());
            int problemsSolved = (int) contestSubs.stream()
                .filter(s -> "OK".equals(s.getVerdict()) && inRound(s, ratedAt))
                .map(s -> s.getProblemIndex())
                .filter(Objects::nonNull)
                .distinct().count();

            int wrongSubs = (int) contestSubs.stream()
                .filter(s -> s.getVerdict() != null && !s.getVerdict().equals("OK"))
                .count();

            // First solve time in minutes
            OptionalLong firstSolveEpoch = contestSubs.stream()
                .filter(s -> "OK".equals(s.getVerdict()) && s.getSubmittedAt() != null)
                .mapToLong(s -> s.getSubmittedAt().getEpochSecond())
                .min();

            Integer firstSolveMins = null;
            if (firstSolveEpoch.isPresent() && rc.getRatingUpdateTimeSeconds() != null) {
                // Approximate: contest start ≈ ratingUpdate - 2 hours
                long contestStartEst = rc.getRatingUpdateTimeSeconds() - 7200;
                firstSolveMins = (int) Math.max(0, (firstSolveEpoch.getAsLong() - contestStartEst) / 60);
            }

            int ratingChange = (rc.getNewRating() != null && rc.getOldRating() != null)
                ? rc.getNewRating() - rc.getOldRating() : 0;

            ContestSummary cs = ContestSummary.builder()
                .user(user)
                .platform("CODEFORCES")
                .contestName(rc.getContestName())
                .contestCfId(cfId)
                .rank(rc.getRank())
                .ratingBefore(rc.getOldRating())
                .ratingAfter(rc.getNewRating())
                .ratingChange(ratingChange)
                .problemsSolved(problemsSolved)
                .totalProblems(problemsIn.getOrDefault(rc.getContestId(), 0))
                .firstSolveMins(firstSolveMins)
                .wrongSubmissions(wrongSubs)
                .penaltyMins(0)
                .contestDate(rc.getRatingUpdateTimeSeconds() != null
                    ? Instant.ofEpochSecond(rc.getRatingUpdateTimeSeconds()) : null)
                .build();

            contestSummaryRepository.save(cs);
            saved++;
        }

        log.info("Saved {} new contest summaries for user {}", saved, userId);
        return saved;
    }
}
