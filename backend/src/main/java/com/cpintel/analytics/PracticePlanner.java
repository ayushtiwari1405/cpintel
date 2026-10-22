package com.cpintel.analytics;

import com.cpintel.roadmap.ProblemRecommender;
import com.cpintel.roadmap.RoadmapTaxonomy;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Decides what goes on a practice sheet: which skills, which problems, in what order, and why.
 *
 * <p>Pure on purpose. It takes the user's per-skill numbers, the roadmap's next skills and a way
 * to list a skill's problems, and returns a sheet; it reads no repository and no clock, so the
 * same inputs always give the same sheet and every rule here can be tested without a database.
 * {@link RecommendationEngine} gathers the inputs and stores what comes out.
 *
 * <h2>The rules</h2>
 *
 * <ul>
 *   <li><b>Evidence first.</b> A skill is ranked on its own numbers only once the user has
 *       attempted {@value #MIN_ATTEMPTS_FOR_SHEET} problems in it; below that a mastery of zero
 *       means "not reached", not "weak". Any places left are filled from the roadmap's next
 *       skills, in the roadmap's order and marked {@link Plan#INFERRED}, so the sheet and the
 *       roadmap cannot point different ways.</li>
 *   <li><b>Nothing solved, nothing twice.</b> Solved problems are never offered as new
 *       practice, only as an explicit {@link Plan#RESOLVE} on a revision item, and no problem
 *       appears twice on a sheet or on two live sheets at once.</li>
 *   <li><b>A climb, not a pile.</b> Each skill gets problems at fixed steps below one target
 *       rating - a warm-up, the core, and the target itself as the stretch - aimed from the
 *       user's rating on that skill when they have one and from where their mastery sits in the
 *       skill's band otherwise.</li>
 *   <li><b>Spread.</b> A daily sheet takes one skill per topic before it takes a second;
 *       weekly plans allow two.</li>
 *   <li><b>Within reach.</b> A skill is ranked only if its easiest problem is one the user has
 *       shown they can solve and they have not long outgrown its hardest, and no ladder climbs
 *       past its own top rung.</li>
 * </ul>
 */
public final class PracticePlanner {

    /** Attempts in a skill before its own numbers rank it. */
    public static final int MIN_ATTEMPTS_FOR_SHEET = 3;

    /** Decay, in mastery points, past which a skill counts as needing revision. */
    public static final double REVISION_DECAY_THRESHOLD = 10.0;

    static final int DAILY_ITEMS = 3;
    static final int WEEKLY_FOCUS_ITEMS = 4;
    static final int CONTEST_ITEMS = 3;

    /** How the daily priority weighs weakness, decay and recent momentum. */
    static final double W_WEAK = 0.5;
    static final double W_DECAY = 0.3;
    static final double W_MOMENTUM = 0.2;

    /** Practice within this many days still counts as momentum, fading linearly. */
    static final int MOMENTUM_DAYS = 14;

    /** A weekly plan's progression slot goes to a fresh skill at least this strong. */
    static final double PROGRESSION_MIN_MASTERY = 50;

    /** Weekly decay past which a skill is labelled for revision rather than practice. */
    static final double WEEKLY_REVISION_DECAY = 20;

    /** A weekly goal is this share of the problems on the plan. */
    static final double WEEKLY_GOAL_SHARE = 2.0 / 3.0;

    /** Contest prep only takes skills whose band comes within this of the target rating. */
    static final int CONTEST_BAND_REACH = 200;

    /** Previously solved problems a revision item asks the user to solve again. */
    static final int RESOLVE_COUNT = 2;

    /** Below this mastery a skill is called weak; at or above it, once known. */
    static final double WEAK_MASTERY = ScoringFormulas.FIT_WEAK_TOPIC_THRESHOLD;

    /** A skill whose hardest problem is this far below the user's level in it is outgrown. */
    static final int OUTGROWN_MARGIN = 200;

    /** A progression slot needs problems at least this far above the skill's target. */
    static final int PROGRESSION_ROOM = 100;

    /** Problem ratings come in steps of this; a rung reaches the nearest problem within half of it. */
    static final int RATING_STEP = 100;

    // Skill ladders end at the skill's target, which is already a stretch (a skill rating plus
    // FIT_SKILL_STRETCH, or the top of where mastery sits in the band): centring on it put the
    // warm-up of a weak skill above the user's own rating. Only progression climbs past it.
    // Contest ladders are around the user's rating itself.
    private static final Ladder DAILY = new Ladder(
        new int[] {-300, -200, -100, 0}, new String[] {Plan.WARM_UP, Plan.CORE, Plan.CORE, Plan.STRETCH});
    private static final Ladder WEEKLY = new Ladder(
        new int[] {-200, -100, 0}, new String[] {Plan.WARM_UP, Plan.CORE, Plan.STRETCH});
    private static final Ladder PROGRESSION = new Ladder(
        new int[] {-100, 0, 100}, new String[] {Plan.CORE, Plan.STRETCH, Plan.STRETCH});
    private static final Ladder CONTEST = new Ladder(
        new int[] {-200, 0, 200}, new String[] {Plan.WARM_UP, Plan.CORE, Plan.STRETCH});
    private static final Ladder CONSOLIDATE = new Ladder(
        new int[] {-200}, new String[] {Plan.CORE});

    /**
     * One skill as the planner sees it. Mastery and decay are 0-100.
     *
     * @param maxSolvedRating the hardest problem solved in it, the evidence of ability when there
     *                        is no skill rating
     */
    public record Skill(
        String nodeKey,
        double mastery,
        double decay,
        int attempted,
        Double skillRating,
        Instant lastPracticedAt,
        Integer maxSolvedRating
    ) {
        public Skill(String nodeKey, double mastery, double decay, int attempted, Double skillRating,
                     Instant lastPracticedAt) {
            this(nodeKey, mastery, decay, attempted, skillRating, lastPracticedAt, null);
        }

        static Skill untouched(String nodeKey) {
            return new Skill(nodeKey, 0, 0, 0, null, null);
        }

        /** What the user has shown they can solve here: the skill rating, else the hardest solve. */
        Double ability() {
            if (skillRating != null) return skillRating;
            return maxSolvedRating == null ? null : maxSolvedRating.doubleValue();
        }

        double retained() {
            return Math.max(0, mastery - decay);
        }
    }

    /** Lists a skill's problems. */
    @FunctionalInterface
    public interface ProblemSource {
        /** Every problem in the skill, each flagged solved or not against {@code solved}. */
        List<ProblemRecommender.Recommended> candidates(RoadmapTaxonomy.NodeDef def, Double skillRating,
                                                        double mastery, Set<String> solved);
    }

    /**
     * @param skills              the user's per-skill rows (any order)
     * @param roadmapNext         the roadmap's next skills, in the roadmap's order
     * @param solved              keys ({@code contestId + index}) of every problem the user has solved
     * @param exclude             keys already on another live sheet
     * @param problemsetAvailable false when the problem list could not be loaded, so the sheet
     *                            can say why it names skills without problems
     */
    public record Inputs(
        List<Skill> skills,
        List<String> roadmapNext,
        Set<String> solved,
        Set<String> exclude,
        boolean problemsetAvailable,
        Instant now
    ) {}

    /** A finished sheet. */
    public record Sheet(List<Plan.Item> items, Plan.Summary summary) {}

    /** Offsets from a target rating, and what each rung is called. */
    private record Ladder(int[] offsets, String[] roles) {}

    /** A skill chosen for a sheet, before its problems are attached. */
    private record Pick(RoadmapTaxonomy.NodeDef def, Skill skill, String reasonCode, String reason,
                        String priority, String basis) {}

    private final ProblemSource source;

    public PracticePlanner(ProblemSource source) {
        this.source = source;
    }

    // -- daily -------------------------------------------------------------

    /**
     * Today's sheet: the skills with the highest priority, which weighs weakness, decay and
     * momentum together, so a weak skill, a fading one and the one the user is in the middle of
     * all have a way onto it.
     */
    public Sheet daily(Inputs in) {
        List<Pick> ranked = measured(in).stream()
            .sorted(Comparator.comparingDouble((Skill s) -> -dailyPriority(s, in.now()))
                .thenComparing(Skill::nodeKey))
            .map(s -> dailyPick(s, in.now()))
            .toList();

        List<Pick> chosen = withRoadmapFallback(select(ranked, DAILY_ITEMS, 1), DAILY_ITEMS, in, null);

        Set<String> used = new HashSet<>(in.exclude());
        List<Plan.Item> items = new ArrayList<>();
        for (Pick p : chosen) items.add(item(p, DAILY, null, used, in, null));
        return new Sheet(items, Plan.Summary.of(basisOf(items), notes(items, in)));
    }

    /** Weakness, decay and momentum, weighted, 0-100. */
    static double dailyPriority(Skill s, Instant now) {
        return W_WEAK * (100 - clamp(s.mastery())) + W_DECAY * clamp(s.decay())
            + W_MOMENTUM * momentum(s, now);
    }

    /** 100 on the day of practice, falling to 0 at {@value #MOMENTUM_DAYS} days. */
    static double momentum(Skill s, Instant now) {
        if (s.lastPracticedAt() == null || now == null) return 0;
        double days = Math.max(0, Duration.between(s.lastPracticedAt(), now).toHours() / 24.0);
        return days >= MOMENTUM_DAYS ? 0 : 100 * (1 - days / MOMENTUM_DAYS);
    }

    /**
     * Says why a skill is on the sheet. Ranking weighs weakness, decay and momentum together;
     * the reason names what the user would recognise. Naming the largest weighted component
     * called a skill at 55% that was practised this morning a "weak area", and so too one known
     * at 80% that had since faded almost entirely.
     */
    private static Pick dailyPick(Skill s, Instant now) {
        RoadmapTaxonomy.NodeDef def = RoadmapTaxonomy.byId(s.nodeKey());
        if (s.decay() >= REVISION_DECAY_THRESHOLD && s.mastery() >= WEAK_MASTERY) {
            return new Pick(def, s, "REVISION_DUE",
                "Revision due - faded from " + Math.round(s.mastery()) + "% to "
                    + Math.round(s.retained()) + "% mastery",
                null, Plan.MEASURED);
        }
        if (s.mastery() < WEAK_MASTERY) {
            return new Pick(def, s, "WEAK_TOPIC",
                "Weak area - mastery " + Math.round(s.mastery()) + "% over "
                    + s.attempted() + " attempted problems",
                null, Plan.MEASURED);
        }
        if (momentum(s, now) > 0) {
            long days = Math.max(0, Duration.between(s.lastPracticedAt(), now).toDays());
            return new Pick(def, s, "MOMENTUM",
                "Keep going - you practised this " + (days == 0 ? "today" : days + "d ago")
                    + ", mastery " + Math.round(s.mastery()) + "%",
                null, Plan.MEASURED);
        }
        return new Pick(def, s, "CONSOLIDATE",
            "Worth consolidating - mastery " + Math.round(s.mastery()) + "%", null, Plan.MEASURED);
    }

    // -- weekly ------------------------------------------------------------

    /**
     * The week's plan: the skills the user retains least - so a strong skill gone stale ranks
     * beside a weak one - and then one progression slot that pushes a strong, fresh skill
     * harder. Each skill is one session.
     */
    public Sheet weekly(Inputs in) {
        List<Skill> measured = measured(in);
        List<Pick> ranked = measured.stream()
            .sorted(Comparator.comparingDouble(Skill::retained).thenComparing(Skill::nodeKey))
            .map(PracticePlanner::weeklyPick)
            .toList();

        List<Pick> chosen = new ArrayList<>(
            withRoadmapFallback(select(ranked, WEEKLY_FOCUS_ITEMS, 2), WEEKLY_FOCUS_ITEMS, in, null));
        progressionPick(measured, keysOf(chosen)).ifPresent(chosen::add);

        Set<String> used = new HashSet<>(in.exclude());
        List<Plan.Item> items = new ArrayList<>();
        int session = 1;
        for (Pick p : chosen) {
            Ladder ladder = "PROGRESSION".equals(p.reasonCode()) ? PROGRESSION : WEEKLY;
            items.add(item(p, ladder, null, used, in, session++));
        }

        int total = items.stream().mapToInt(i -> i.problems().size()).sum();
        return new Sheet(items, new Plan.Summary(basisOf(items), notes(items, in),
            total == 0 ? null : (int) Math.ceil(total * WEEKLY_GOAL_SHARE), null, null, null, null));
    }

    private static Pick weeklyPick(Skill s) {
        RoadmapTaxonomy.NodeDef def = RoadmapTaxonomy.byId(s.nodeKey());
        if (s.decay() > WEEKLY_REVISION_DECAY) {
            return new Pick(def, s, "REVISION_DUE",
                "Fading - " + Math.round(s.decay()) + "% of this has decayed", "REVISION", Plan.MEASURED);
        }
        if (s.mastery() < 30) {
            return new Pick(def, s, "WEAK_TOPIC",
                "Barely started - mastery " + Math.round(s.mastery()) + "%", "NEW", Plan.MEASURED);
        }
        return new Pick(def, s, "CONSOLIDATE",
            "Worth consolidating - mastery " + Math.round(s.mastery()) + "%", "PRACTICE", Plan.MEASURED);
    }

    /** The strongest skill that is still fresh, to be pushed rather than repaired. */
    private static Optional<Pick> progressionPick(List<Skill> measured, Set<String> taken) {
        return measured.stream()
            .filter(s -> !taken.contains(s.nodeKey()))
            .filter(s -> s.decay() < REVISION_DECAY_THRESHOLD && s.mastery() >= PROGRESSION_MIN_MASTERY)
            // Somewhere to climb to: a skill that tops out at the user's level has nothing harder.
            .filter(s -> {
                RoadmapTaxonomy.NodeDef def = RoadmapTaxonomy.byId(s.nodeKey());
                return def.maxRating() >= targetFor(def, s) + PROGRESSION_ROOM;
            })
            .min(Comparator.comparingDouble((Skill s) -> -s.mastery()).thenComparing(Skill::nodeKey))
            .map(s -> new Pick(RoadmapTaxonomy.byId(s.nodeKey()), s, "PROGRESSION",
                "Strong and fresh - mastery " + Math.round(s.mastery())
                    + "%: step up to harder problems",
                "PROGRESS", Plan.MEASURED));
    }

    // -- contest preparation -----------------------------------------------

    /**
     * Preparation for a rated round at {@code rating}: the weakest skills that such a round
     * actually tests, each with a quick warm-up below the rating, a core problem at it and a
     * stretch above it - the one that, solved in the round, is the rating gain.
     *
     * @param ratingBasis {@link Plan#MEASURED} for the user's Codeforces rating, {@link Plan#INFERRED}
     *                    for an estimate from what they have solved
     */
    public Sheet contestPrep(Inputs in, int rating, String ratingBasis,
                             Plan.Contest contest, Plan.ContestRecord record) {
        List<Pick> ranked = measured(in).stream()
            .filter(s -> reaches(RoadmapTaxonomy.byId(s.nodeKey()), rating))
            .sorted(Comparator.comparingDouble(Skill::retained).thenComparing(Skill::nodeKey))
            .map(s -> new Pick(RoadmapTaxonomy.byId(s.nodeKey()), s, "CONTEST_STAPLE",
                "Rounds at your level test this, and it is one of your weaker skills - mastery "
                    + Math.round(s.mastery()) + "%",
                null, Plan.MEASURED))
            .toList();

        List<Pick> chosen = withRoadmapFallback(select(ranked, CONTEST_ITEMS, 1), CONTEST_ITEMS, in, rating);

        Set<String> used = new HashSet<>(in.exclude());
        List<Plan.Item> items = new ArrayList<>();
        for (Pick p : chosen) items.add(item(p, CONTEST, rating, used, in, null));

        List<String> notes = new ArrayList<>(notes(items, in));
        if (Plan.INFERRED.equals(ratingBasis)) {
            notes.add("No Codeforces rating yet, so the plan is aimed at an estimated " + rating
                + " from the problems you have solved.");
        }
        if (record != null && record.contests() > 0) {
            notes.add(String.format(java.util.Locale.ROOT,
                "In your last %d rated rounds you solved %.1f problems on average (rating change %+.0f). "
                    + "The stretch problems are aimed at the next one.",
                record.contests(), record.avgSolved(), record.avgRatingChange()));
        }
        if (contest == null) {
            notes.add("No upcoming rated Codeforces round for your rating was found, so this is "
                + "general preparation.");
        }
        return new Sheet(items, new Plan.Summary(basisOf(items), List.copyOf(notes), null,
            rating, ratingBasis, contest, record));
    }

    private static boolean reaches(RoadmapTaxonomy.NodeDef def, int rating) {
        return def != null
            && def.minRating() <= rating + CONTEST_BAND_REACH
            && def.maxRating() >= rating - CONTEST_BAND_REACH;
    }

    // -- revision ----------------------------------------------------------

    /**
     * What to do for a skill that has faded: solve again the problems in it the user solved
     * longest ago, then one new problem comfortably under their target to confirm it has come
     * back.
     *
     * @param solvedAt when each solved problem was last worked on, by key
     * @param used     keys already given to another revision item; the ones chosen here are
     *                 added, so a problem filed under two faded skills is offered once
     */
    public List<Plan.Problem> revision(String nodeKey, Skill skill, Set<String> solved,
                                       Map<String, Instant> solvedAt, Set<String> used) {
        RoadmapTaxonomy.NodeDef def = RoadmapTaxonomy.byId(nodeKey);
        if (def == null) return List.of();
        Skill s = skill == null ? Skill.untouched(nodeKey) : skill;
        List<ProblemRecommender.Recommended> candidates =
            source.candidates(def, s.skillRating(), s.mastery(), solved);

        List<Plan.Problem> out = new ArrayList<>();
        candidates.stream()
            .filter(c -> c.contestId() != null && c.index() != null)
            .filter(c -> solved.contains(key(c)) && !used.contains(key(c)))
            .sorted(Comparator.comparing((ProblemRecommender.Recommended c) ->
                    solvedAt.getOrDefault(key(c), Instant.MAX))
                .thenComparing(PracticePlanner::key))
            .limit(RESOLVE_COUNT)
            .forEach(c -> out.add(problem(c, Plan.RESOLVE, true)));

        out.addAll(climb(candidates, targetFor(def, s), CONSOLIDATE, keysOf(out, used), solved));
        for (Plan.Problem p : out) used.add(p.key());
        return out;
    }

    // -- shared ------------------------------------------------------------

    /**
     * Whether a skill is worth practising at the user's level: its easiest problem is one they
     * have shown they can solve (to the problem grid), and its hardest is not long behind them.
     * Without evidence of ability, every skill is.
     *
     * <p>A beginner who tried a few problems far above their level has a mastery of zero there,
     * which ranked it as their weakest skill and put a 1300 warm-up in front of somebody rated
     * 900. And a 2200 player whose mastery in a 1500-capped skill reads low was sent 1200s.
     */
    static boolean inReach(RoadmapTaxonomy.NodeDef def, Skill s) {
        Double ability = s.ability();
        if (ability == null) return true;
        return def.minRating() <= gridCeiling(ability) && def.maxRating() >= ability - OUTGROWN_MARGIN;
    }

    /** {@code rating} rounded up to the problem grid. */
    static int gridCeiling(double rating) {
        return (int) Math.ceil(rating / RATING_STEP) * RATING_STEP;
    }

    /** Skills with enough evidence to be ranked on their own numbers. */
    private static List<Skill> measured(Inputs in) {
        Map<String, Skill> byKey = new HashMap<>();
        for (Skill s : in.skills()) {
            if (s == null || s.nodeKey() == null) continue;
            if (s.attempted() < MIN_ATTEMPTS_FOR_SHEET) continue;
            RoadmapTaxonomy.NodeDef def = RoadmapTaxonomy.byId(s.nodeKey());
            if (def == null || !inReach(def, s)) continue;
            byKey.putIfAbsent(s.nodeKey(), s);
        }
        return byKey.values().stream().sorted(Comparator.comparing(Skill::nodeKey)).toList();
    }

    /**
     * Takes {@code n} from {@code ranked} in order, at most {@code perTopic} from any one topic
     * while others are left, then tops up from what was skipped.
     */
    private static List<Pick> select(List<Pick> ranked, int n, int perTopic) {
        List<Pick> chosen = new ArrayList<>();
        Map<String, Integer> perTopicCount = new HashMap<>();
        List<Pick> skipped = new ArrayList<>();
        for (Pick p : ranked) {
            if (chosen.size() >= n) break;
            String topic = p.def().rollupTopic();
            if (perTopicCount.getOrDefault(topic, 0) >= perTopic) {
                skipped.add(p);
                continue;
            }
            perTopicCount.merge(topic, 1, Integer::sum);
            chosen.add(p);
        }
        for (Pick p : skipped) {
            if (chosen.size() >= n) break;
            chosen.add(p);
        }
        return chosen;
    }

    /**
     * Fills any places left with the roadmap's next skills, in its order. These are where the
     * user is going rather than where they are weak, and are marked as inferred.
     *
     * @param contestRating when set, only skills whose band reaches it
     */
    private static List<Pick> withRoadmapFallback(List<Pick> chosen, int n, Inputs in, Integer contestRating) {
        if (chosen.size() >= n || in.roadmapNext() == null) return chosen;
        Map<String, Skill> byKey = new HashMap<>();
        for (Skill s : in.skills()) if (s != null && s.nodeKey() != null) byKey.putIfAbsent(s.nodeKey(), s);

        List<Pick> out = new ArrayList<>(chosen);
        Set<String> taken = keysOf(out);
        for (String key : in.roadmapNext()) {
            if (out.size() >= n) break;
            RoadmapTaxonomy.NodeDef def = RoadmapTaxonomy.byId(key);
            if (def == null || !taken.add(key)) continue;
            if (contestRating != null && !reaches(def, contestRating)) continue;
            Skill s = byKey.getOrDefault(key, Skill.untouched(key));
            out.add(new Pick(def, s, "NEXT_ON_ROADMAP",
                "Next on your roadmap - not enough attempts here yet to measure it",
                null, Plan.INFERRED));
        }
        return out;
    }

    private Plan.Item item(Pick p, Ladder ladder, Integer fixedTarget, Set<String> used,
                           Inputs in, Integer session) {
        RoadmapTaxonomy.NodeDef def = p.def();
        Skill s = p.skill();
        int target = fixedTarget != null ? fixedTarget : targetFor(def, s);

        List<Plan.Problem> problems = climb(
            source.candidates(def, s.skillRating(), s.mastery(), in.solved()),
            target, ladder, used, in.solved());
        for (Plan.Problem pr : problems) used.add(pr.key());

        return new Plan.Item(def.id(), def.displayName(), def.track(), def.rollupTopic(), def.blurb(),
            Math.round(s.mastery()), Math.round(s.decay()), target,
            p.reason(), p.reasonCode(), p.priority(), p.basis(), session, problems);
    }

    /**
     * One problem per rung of the ladder: the unsolved, unused problem nearest each rung's
     * rating. Ties go to the better fit, then the newer contest, then the index, so the choice
     * never depends on the order the candidates arrived in.
     *
     * <p>Nothing above the top rung (or the aim, if that is higher) is taken. A skill whose band
     * starts above the lower rungs used to have its ladder pushed up past its own aim - a 2350
     * aim ended in a 2500 "stretch" - so a short band now gives fewer problems instead of harder
     * ones. Only when everything up to the aim is solved are the next problems above it offered.
     * Each problem is named for the rung it is nearest, and they come back easiest first.
     */
    private static List<Plan.Problem> climb(List<ProblemRecommender.Recommended> candidates, int target,
                                            Ladder ladder, Set<String> used, Set<String> solved) {
        int top = Math.max(target, target + ladder.offsets()[ladder.offsets().length - 1]) + RATING_STEP / 2;
        List<ProblemRecommender.Recommended> open = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (ProblemRecommender.Recommended c : candidates) {
            if (c.contestId() == null || c.index() == null || c.rating() == null) continue;
            String key = key(c);
            if (c.solved() || solved.contains(key) || used.contains(key) || !seen.add(key)) continue;
            open.add(c);
        }
        List<ProblemRecommender.Recommended> pool = new ArrayList<>();
        for (ProblemRecommender.Recommended c : open) if (c.rating() <= top) pool.add(c);
        // Everything up to the aim already solved: that has earned the next ones up.
        if (pool.isEmpty()) {
            open.sort(Comparator.comparingInt(ProblemRecommender.Recommended::rating).thenComparing(PracticePlanner::key));
            pool.addAll(open.subList(0, Math.min(open.size(), ladder.offsets().length)));
        }

        List<ProblemRecommender.Recommended> picked = new ArrayList<>();
        for (int i = 0; i < ladder.offsets().length && !pool.isEmpty(); i++) {
            int rung = target + ladder.offsets()[i];
            ProblemRecommender.Recommended best = pool.stream()
                .min(Comparator.comparingInt((ProblemRecommender.Recommended c) -> Math.abs(c.rating() - rung))
                    .thenComparing(Comparator.comparingDouble(ProblemRecommender.Recommended::fit).reversed())
                    .thenComparing(Comparator.comparingInt(ProblemRecommender.Recommended::contestId).reversed())
                    .thenComparing(ProblemRecommender.Recommended::index))
                .orElseThrow();
            pool.remove(best);
            picked.add(best);
        }
        picked.sort(Comparator.comparingInt(ProblemRecommender.Recommended::rating)
            .thenComparing(PracticePlanner::key));

        List<Plan.Problem> out = new ArrayList<>(picked.size());
        for (ProblemRecommender.Recommended c : picked) out.add(problem(c, nearestRole(ladder, target, c.rating()), false));
        return out;
    }

    /** The role of the rung nearest {@code rating}; between two, the lower. */
    private static String nearestRole(Ladder ladder, int target, int rating) {
        int best = 0;
        for (int i = 1; i < ladder.offsets().length; i++) {
            if (Math.abs(target + ladder.offsets()[i] - rating) < Math.abs(target + ladder.offsets()[best] - rating)) best = i;
        }
        return ladder.roles()[best];
    }

    /** The rating to aim at in a skill: just above the user's skill rating, or by mastery. */
    static int targetFor(RoadmapTaxonomy.NodeDef def, Skill s) {
        double aim = s.skillRating() != null
            ? s.skillRating() + ScoringFormulas.FIT_SKILL_STRETCH
            : ScoringFormulas.targetRating(s.mastery(), def.minRating(), def.maxRating());
        // With only a hardest solve to go on, aim no further past it than a skill rating would.
        if (s.skillRating() == null && s.maxSolvedRating() != null) {
            aim = Math.min(aim, s.maxSolvedRating() + ScoringFormulas.FIT_SKILL_STRETCH);
        }
        int lo = Math.min(def.minRating(), def.maxRating());
        int hi = Math.max(def.minRating(), def.maxRating());
        return (int) Math.round(Math.max(lo, Math.min(hi, aim)));
    }

    private static Plan.Problem problem(ProblemRecommender.Recommended c, String role, boolean solved) {
        return new Plan.Problem(c.contestId(), c.index(), c.name(), c.rating(), solved, c.fit(), role,
            c.practicePath(), c.judgeUrl());
    }

    private static String basisOf(List<Plan.Item> items) {
        if (items.isEmpty()) return Plan.NONE;
        boolean measured = items.stream().anyMatch(i -> Plan.MEASURED.equals(i.basis()));
        boolean inferred = items.stream().anyMatch(i -> Plan.INFERRED.equals(i.basis()));
        return measured && inferred ? Plan.PARTIAL : measured ? Plan.MEASURED : Plan.INFERRED;
    }

    private static List<String> notes(List<Plan.Item> items, Inputs in) {
        List<String> notes = new ArrayList<>();
        if (items.isEmpty()) {
            notes.add("Nothing to plan from yet. Link and sync your Codeforces account, and your "
                + "roadmap's first skills will appear here.");
        } else if (items.stream().anyMatch(i -> Plan.INFERRED.equals(i.basis()))) {
            notes.add("Skills marked \"from your roadmap\" have fewer than " + MIN_ATTEMPTS_FOR_SHEET
                + " attempted problems, so they come from your roadmap's next steps rather than "
                + "from measured weakness.");
        }
        if (!in.problemsetAvailable() && !items.isEmpty()) {
            notes.add("The Codeforces problem list could not be loaded, so some skills have no "
                + "problems yet. They will be filled in on the next refresh.");
        }
        return List.copyOf(notes);
    }

    /** {@code used} plus the keys of {@code problems}, without changing {@code used}. */
    private static Set<String> keysOf(List<Plan.Problem> problems, Set<String> used) {
        Set<String> keys = new HashSet<>(used);
        for (Plan.Problem p : problems) keys.add(p.key());
        return keys;
    }

    private static Set<String> keysOf(List<Pick> picks) {
        Set<String> keys = new HashSet<>();
        for (Pick p : picks) keys.add(p.def().id());
        return keys;
    }

    private static String key(ProblemRecommender.Recommended c) {
        return c.contestId() + c.index();
    }

    private static double clamp(double v) {
        return Math.max(0, Math.min(100, v));
    }
}
