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
import com.cpintel.roadmap.GauntletBank;
import com.cpintel.roadmap.GauntletDto;
import com.cpintel.roadmap.RoadmapTaxonomy;
import com.cpintel.roadmap.SkillAttribution;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * A skill rating for every roadmap node and every topic, from whichever source the person chose.
 *
 * <h2>Two bases, and the choice between them</h2>
 *
 * <ul>
 *   <li><b>CODEFORCES</b>: their rating, moved per skill by how they did on that skill's problems
 *       in their rated rounds ({@link SkillRating}).</li>
 *   <li><b>GAUNTLET</b>: the area ratings from CPIntel's placement test, for everyone whose
 *       Codeforces rating does not describe them — rounds sat with an AI's help or somebody
 *       else's, or an account they no longer use. Rated rounds are then ignored entirely, because
 *       they are exactly the evidence being set aside.</li>
 * </ul>
 *
 * <p>{@code AUTO} takes rated rounds when there are any, then the gauntlet. Asking for
 * {@code GAUNTLET} never falls back to Codeforces: the person said they do not trust it. With
 * neither, there is no rating to show, and the analytics show practice coverage instead — what
 * has been solved and how hard — rather than a number the data cannot support.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SkillRatingService {

    public static final String AUTO = "AUTO";
    public static final String CODEFORCES = "CODEFORCES";
    public static final String GAUNTLET = "GAUNTLET";

    /**
     * Uncertainty of a gauntlet placement, in rating points. Its tiers are 400 points apart, so
     * a placement only says which tier somebody stopped in; this is roughly that spread.
     */
    public static final double GAUNTLET_SD = 150.0;

    /**
     * Accounts made since 2020 go into their first round shown at 0 while Codeforces rates them
     * at 1400 underneath, and the shown rating catches up over six rounds by these amounts.
     * Until it has, the shown rating understates the person by what is still to be paid.
     */
    static final int[] NEW_ACCOUNT_BONUSES = { 500, 350, 250, 150, 100, 50 };

    private final ContestSummaryRepository contests;
    private final CfSubmissionRepository submissions;
    private final CfProblemsetClient problemset;
    private final PlacementResultRepository placements;
    private final ObjectMapper json;

    /** One skill's rating. {@code problems} is how many rated-round problems it rests on. */
    public record Skill(double rating, double sd, int problems) {}

    /**
     * Everything one person's skills are rated from.
     *
     * @param basis     CODEFORCES or GAUNTLET; null when there is nothing to rate from
     * @param requested what the person chose: AUTO, CODEFORCES or GAUNTLET
     * @param rating    the rating every skill starts from: their Codeforces rating, or the
     *                  average of their gauntlet areas
     * @param nodes     every roadmap node, by id, when there is a basis; empty otherwise
     * @param topics    every roll-up topic, likewise
     */
    public record Profile(String basis, String requested, Integer rating,
                          boolean codeforcesAvailable, boolean gauntletAvailable,
                          Map<String, Skill> nodes, Map<String, Skill> topics) {
        public static Profile none(String requested, boolean cf, boolean gauntlet) {
            return new Profile(null, requested, null, cf, gauntlet, Map.of(), Map.of());
        }
    }

    /**
     * Which basis applies and what is available, without rating anything — what the settings
     * control and the analytics header show.
     *
     * @param basis  CODEFORCES or GAUNTLET, or null when there is nothing to rate from
     * @param rating what every skill starts from on that basis; null with no basis
     */
    public record Summary(String basis, String requested, Integer rating,
                          boolean codeforcesAvailable, boolean gauntletAvailable) {}

    public Summary summary(User user) {
        return resolve(user).summary();
    }

    public Profile profile(User user) {
        Resolved r = resolve(user);
        Summary s = r.summary();
        if (s.basis() == null) {
            return Profile.none(s.requested(), s.codeforcesAvailable(), s.gauntletAvailable());
        }
        return CODEFORCES.equals(s.basis())
            ? fromRounds(user.getUserId(), s.requested(), r.rounds(), s.gauntletAvailable())
            : fromGauntlet(s.requested(), r.placement().get(), s.codeforcesAvailable());
    }

    private record Resolved(Summary summary, List<Round> rounds,
                            Optional<PlacementResult> placement) {}

    private Resolved resolve(User user) {
        String requested = user.getSkillBasis() == null ? AUTO : user.getSkillBasis();
        List<Round> rounds = ratedRounds(user.getUserId());
        Optional<PlacementResult> placement =
            placements.findFirstByUserIdOrderByCreatedAtDesc(user.getUserId());
        boolean cf = !rounds.isEmpty();
        boolean gauntlet = placement.isPresent();

        String basis = switch (requested) {
            case GAUNTLET -> gauntlet ? GAUNTLET : null;
            default -> cf ? CODEFORCES : (gauntlet ? GAUNTLET : null);
        };
        Integer rating = basis == null ? null
            : CODEFORCES.equals(basis) ? rounds.get(rounds.size() - 1).ratingAfter()
            : placement.get().getOverallRating();
        return new Resolved(new Summary(basis, requested, rating, cf, gauntlet), rounds, placement);
    }

    // ── Codeforces ─────────────────────────────────────────────────────────

    /**
     * One rated round, with the person's true rating going in and coming out.
     *
     * @param measured false for a first round on the old system, which went in at a flat 1500
     *                 placeholder: it still sets the rating after it, but says nothing about
     *                 which skills beat that rating, so it is not used as evidence
     */
    record Round(String contestId, Instant at, int ratingBefore, int ratingAfter,
                 boolean measured) {}

    List<Round> ratedRounds(Long userId) {
        List<ContestSummary> rows = new ArrayList<>(
            contests.findByUserUserIdAndPlatform(userId, "CODEFORCES"));
        rows.removeIf(r -> r.getContestDate() == null || r.getRatingBefore() == null
            || r.getRatingAfter() == null || r.getContestCfId() == null);
        rows.sort(Comparator.comparing(ContestSummary::getContestDate));
        boolean newSystem = !rows.isEmpty() && rows.get(0).getRatingBefore() == 0;

        List<Round> out = new ArrayList<>();
        for (int k = 0; k < rows.size(); k++) {
            ContestSummary r = rows.get(k);
            int before = r.getRatingBefore() + unpaid(newSystem, k);
            int after = r.getRatingAfter() + unpaid(newSystem, k + 1);
            out.add(new Round(r.getContestCfId(), r.getContestDate(), before, after,
                newSystem || k > 0));
        }
        return out;
    }

    static int unpaid(boolean newSystem, int roundsDone) {
        if (!newSystem || roundsDone >= NEW_ACCOUNT_BONUSES.length) return 0;
        int paid = 0;
        for (int i = 0; i < roundsDone; i++) paid += NEW_ACCOUNT_BONUSES[i];
        return 1400 - paid;
    }

    /**
     * The problem list rated rounds are read against could not be had — Codeforces unreachable
     * since the last restart. Rating from it anyway would find no evidence and quietly put every
     * skill back to the person's overall rating, so the caller keeps the ratings it has.
     */
    public static class Unavailable extends RuntimeException {
        Unavailable() { super("Codeforces' problem list is unavailable"); }
    }

    private Profile fromRounds(Long userId, String requested, List<Round> rounds,
                               boolean gauntlet) {
        int rating = rounds.get(rounds.size() - 1).ratingAfter();
        Map<String, List<CfModels.Submission.Problem>> byContest = problemsByContest();
        if (byContest.isEmpty()) throw new Unavailable();
        Set<String> solvedLive = solvedInRounds(userId, rounds);

        Map<String, List<SkillRating.Evidence>> byNode = new HashMap<>();
        Map<String, List<SkillRating.Evidence>> byTopic = new HashMap<>();
        Instant now = Instant.now();
        for (Round round : rounds) {
            if (!round.measured()) continue;
            double ageDays = Duration.between(round.at(), now).toHours() / 24.0;
            for (CfModels.Submission.Problem p : byContest.getOrDefault(round.contestId(), List.of())) {
                if (p.getRating() == null || p.getTags() == null || p.getTags().isEmpty()) continue;
                var evidence = new SkillRating.Evidence(p.getRating(), round.ratingBefore(),
                    solvedLive.contains(round.contestId() + "/" + p.getIndex()), ageDays);
                Set<String> topics = new LinkedHashSet<>();
                for (String node : SkillAttribution.nodesFor(p.getRating(), new HashSet<>(p.getTags()))) {
                    byNode.computeIfAbsent(node, k -> new ArrayList<>()).add(evidence);
                    RoadmapTaxonomy.NodeDef def = RoadmapTaxonomy.byId(node);
                    if (def != null) topics.add(def.rollupTopic());
                }
                for (String t : topics) byTopic.computeIfAbsent(t, k -> new ArrayList<>()).add(evidence);
            }
        }

        Map<String, Skill> nodes = new LinkedHashMap<>();
        for (RoadmapTaxonomy.NodeDef def : RoadmapTaxonomy.NODES) {
            nodes.put(def.id(), skill(rating, byNode.get(def.id())));
        }
        Map<String, Skill> topics = new LinkedHashMap<>();
        for (String topic : RoadmapTaxonomy.rollupTopics()) {
            topics.put(topic, skill(rating, byTopic.get(topic)));
        }
        return new Profile(CODEFORCES, requested, rating, true, gauntlet, nodes, topics);
    }

    private static Skill skill(int rating, List<SkillRating.Evidence> evidence) {
        SkillRating.Estimate e = SkillRating.estimate(evidence);
        return new Skill(rating + e.offset(), e.sd(), e.problems());
    }

    /** contestId/index of every problem accepted while the person was competing in that round. */
    private Set<String> solvedInRounds(Long userId, List<Round> rounds) {
        Map<String, Instant> roundEnd = new HashMap<>();
        for (Round r : rounds) roundEnd.put(r.contestId(), r.at());
        Set<String> out = new HashSet<>();
        for (CfSubmission s : submissions.findByUserId(userId)) {
            if (!"OK".equals(s.getVerdict()) || s.getContestId() == null) continue;
            String contest = String.valueOf(s.getContestId());
            Instant end = roundEnd.get(contest);
            if (end == null) continue;
            boolean live = com.cpintel.service.ContestSyncService.inRound(s, end);
            if (live) out.add(contest + "/" + s.getProblemIndex());
        }
        return out;
    }

    private Map<String, List<CfModels.Submission.Problem>> problemsByContest() {
        Map<String, List<CfModels.Submission.Problem>> out = new HashMap<>();
        for (CfModels.Submission.Problem p : problemset.getAllProblems()) {
            if (p.getContestId() == null) continue;
            out.computeIfAbsent(String.valueOf(p.getContestId()), k -> new ArrayList<>()).add(p);
        }
        return out;
    }

    // ── Gauntlet ───────────────────────────────────────────────────────────

    private Profile fromGauntlet(String requested, PlacementResult placement, boolean cf) {
        Map<String, Integer> byTrack = new HashMap<>();
        try {
            List<GauntletDto.SectionResult> sections = json.readValue(placement.getSections(),
                new TypeReference<List<GauntletDto.SectionResult>>() {});
            for (GauntletDto.SectionResult r : sections) {
                GauntletBank.SECTIONS.stream().filter(s -> s.id().equals(r.section())).findFirst()
                    .ifPresent(s -> s.tracks().forEach(t -> byTrack.put(t, r.rating())));
            }
        } catch (Exception e) {
            log.warn("Unreadable placement result {}: {}", placement.getResultId(), e.getMessage());
        }
        int overall = placement.getOverallRating() == null ? GauntletBank.FLOOR_RATING
            : placement.getOverallRating();

        Map<String, Skill> nodes = new LinkedHashMap<>();
        Map<String, List<Integer>> topicRatings = new LinkedHashMap<>();
        for (RoadmapTaxonomy.NodeDef def : RoadmapTaxonomy.NODES) {
            int r = byTrack.getOrDefault(def.track(), overall);
            nodes.put(def.id(), new Skill(r, GAUNTLET_SD, 0));
            topicRatings.computeIfAbsent(def.rollupTopic(), k -> new ArrayList<>()).add(r);
        }
        Map<String, Skill> topics = new LinkedHashMap<>();
        topicRatings.forEach((t, rs) -> topics.put(t,
            new Skill(rs.stream().mapToInt(Integer::intValue).average().orElse(overall),
                GAUNTLET_SD, 0)));
        return new Profile(GAUNTLET, requested, overall, cf, true, nodes, topics);
    }
}
