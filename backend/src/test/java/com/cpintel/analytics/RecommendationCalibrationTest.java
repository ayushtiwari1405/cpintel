package com.cpintel.analytics;

import com.cpintel.roadmap.ProblemRecommender;
import com.cpintel.roadmap.RoadmapTaxonomy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The planner against seven representative users, built on real skill-tree nodes and a problem
 * list that fills each node's own rating band.
 *
 * <p>These are synthetic histories. They show that the rules do what they say for each kind of
 * user - warm-ups the user has already shown they can solve, stretches within reach, weak and
 * faded skills first, nothing solved or repeated - not that any user learns faster for it. That
 * would need real outcomes, which this repository does not have.
 */
class RecommendationCalibrationTest {

    private static final Instant NOW = Instant.parse("2026-10-22T09:00:00Z");

    /** Every node has a problem every 100 points across its band, plus an unrated one. */
    private static List<ProblemRecommender.Recommended> problemsFor(RoadmapTaxonomy.NodeDef def, Set<String> solved) {
        int contestId = 1000 + RoadmapTaxonomy.NODES.indexOf(def);
        List<ProblemRecommender.Recommended> out = new ArrayList<>();
        for (int r = def.minRating(); r <= def.maxRating(); r += 100) {
            String index = "P" + r;
            out.add(new ProblemRecommender.Recommended(contestId, index, def.id() + " " + r, r, List.of(),
                solved.contains(contestId + index), 50.0, "/practice", null));
        }
        out.add(new ProblemRecommender.Recommended(contestId, "U", "unrated", null, List.of(), false, 0, "/practice", null));
        return out;
    }

    private final PracticePlanner planner = new PracticePlanner((def, sr, m, solved) -> problemsFor(def, solved));

    /** The first nodes (taxonomy order) matching {@code p}, one per topic. */
    private static List<RoadmapTaxonomy.NodeDef> nodes(int n, Predicate<RoadmapTaxonomy.NodeDef> p) {
        List<RoadmapTaxonomy.NodeDef> out = new ArrayList<>();
        Set<String> topics = new HashSet<>();
        for (RoadmapTaxonomy.NodeDef d : RoadmapTaxonomy.NODES) {
            if (out.size() == n) break;
            if (p.test(d) && topics.add(d.rollupTopic())) out.add(d);
        }
        assertEquals(n, out.size(), "taxonomy has too few nodes for this persona");
        return out;
    }

    private static Instant daysAgo(int d) {
        return NOW.minus(Duration.ofDays(d));
    }

    private static PracticePlanner.Skill skill(RoadmapTaxonomy.NodeDef d, double mastery, double decay, int attempted,
                                               Double skillRating, int practisedDaysAgo, Integer maxSolved) {
        return new PracticePlanner.Skill(d.id(), mastery, decay, attempted, skillRating, daysAgo(practisedDaysAgo), maxSolved);
    }

    private static PracticePlanner.Inputs in(List<PracticePlanner.Skill> skills, List<String> roadmap) {
        return new PracticePlanner.Inputs(skills, roadmap, Set.of(), Set.of(), true, NOW);
    }

    /** The rating a user has shown in a skill, rounded up to the problem grid (ratings are in 100s). */
    private static Integer shownCeiling(List<PracticePlanner.Skill> skills, String key) {
        return skills.stream().filter(s -> s.nodeKey().equals(key)).findFirst()
            .map(s -> s.skillRating() != null ? s.skillRating() : s.maxSolvedRating() == null ? null : s.maxSolvedRating().doubleValue())
            .map(a -> (int) Math.ceil(a / 100.0) * 100)
            .orElse(null);
    }

    /** What every sheet must satisfy, whoever it is for. */
    private static void sane(PracticePlanner.Sheet sheet, List<PracticePlanner.Skill> skills) {
        List<String> keys = new ArrayList<>();
        for (Plan.Item item : sheet.items()) {
            assertNotNull(item.reason());
            assertNotNull(item.reasonCode());
            Integer ceiling = shownCeiling(skills, item.nodeKey());
            for (Plan.Problem p : item.problems()) {
                assertNotNull(p.rating(), "unrated problems are never offered");
                assertFalse(p.solved(), "nothing solved is offered as new");
                keys.add(p.key());
                if (ceiling != null && Plan.WARM_UP.equals(p.role())) {
                    assertTrue(p.rating() <= ceiling, item.title() + ": warm-up " + p.rating()
                        + " above the user's shown " + ceiling);
                }
                if (ceiling != null && !"PROGRESSION".equals(item.reasonCode())) {
                    assertTrue(p.rating() <= ceiling + ScoringFormulas.FIT_SKILL_STRETCH + 50,
                        item.title() + ": " + p.rating() + " is out of reach of " + ceiling);
                }
            }
            List<Integer> ratings = item.problems().stream().map(Plan.Problem::rating).toList();
            List<Integer> sorted = new ArrayList<>(ratings);
            Collections.sort(sorted);
            assertEquals(sorted, ratings, item.title() + ": problems climb");
        }
        assertEquals(new HashSet<>(keys).size(), keys.size(), "no problem twice");
    }

    private static List<String> keysOf(PracticePlanner.Sheet sheet) {
        return sheet.items().stream().map(Plan.Item::nodeKey).toList();
    }

    // -- 1. new user ----------------------------------------------------------------------

    @Test
    @DisplayName("1. new user: the roadmap's first skills, from the bottom of each band, marked inferred")
    void newUser() {
        // The roadmap's next skills for somebody new are its lowest ones.
        List<RoadmapTaxonomy.NodeDef> roots = nodes(3, d -> d.minRating() <= 900);
        var sheet = planner.daily(in(List.of(), roots.stream().map(RoadmapTaxonomy.NodeDef::id).toList()));

        sane(sheet, List.of());
        assertEquals(Plan.INFERRED, sheet.summary().basis());
        for (Plan.Item item : sheet.items()) {
            RoadmapTaxonomy.NodeDef def = RoadmapTaxonomy.byId(item.nodeKey());
            assertEquals(def.minRating(), item.problems().get(0).rating(), "starts at the easiest the skill has");
        }
    }

    // -- 2. beginner ----------------------------------------------------------------------

    @Test
    @DisplayName("2. beginner rated ~900: problems they can do, and no skill whose easiest problem is beyond them")
    void beginner() {
        List<RoadmapTaxonomy.NodeDef> easy = nodes(2, d -> d.minRating() <= 900);
        RoadmapTaxonomy.NodeDef tooHard = nodes(1, d -> d.minRating() >= 1300 && d.maxRating() - d.minRating() >= 300).get(0);
        List<PracticePlanner.Skill> skills = List.of(
            skill(easy.get(0), 35, 0, 8, 900.0, 3, 900),
            skill(easy.get(1), 25, 0, 6, 850.0, 5, 800),
            // Tried a few problems far above their level and solved none: the weakest number on
            // paper, but not a skill they can practise yet.
            skill(tooHard, 0, 0, 4, 880.0, 10, null));

        var daily = planner.daily(in(skills, List.of()));
        var weekly = planner.weekly(in(skills, List.of()));

        sane(daily, skills);
        sane(weekly, skills);
        assertFalse(keysOf(daily).contains(tooHard.id()), "out of reach: " + keysOf(daily));
        assertFalse(keysOf(weekly).contains(tooHard.id()));
        assertEquals(Set.of(easy.get(0).id(), easy.get(1).id()), new HashSet<>(keysOf(daily)));
    }

    // -- 3. improving ---------------------------------------------------------------------

    @Test
    @DisplayName("3. steadily improving: the skills in hand are kept going, aimed above their recent level")
    void improving() {
        List<RoadmapTaxonomy.NodeDef> mid = nodes(4, d -> d.minRating() <= 1300 && d.maxRating() >= 1600);
        List<PracticePlanner.Skill> skills = List.of(
            skill(mid.get(0), 55, 0, 25, 1400.0, 1, 1500),
            skill(mid.get(1), 50, 0, 20, 1350.0, 2, 1400),
            skill(mid.get(2), 60, 0, 30, 1450.0, 0, 1500),
            skill(mid.get(3), 70, 0, 40, 1500.0, 30, 1600));

        var daily = planner.daily(in(skills, List.of()));

        sane(daily, skills);
        assertTrue(daily.items().stream().anyMatch(i -> "MOMENTUM".equals(i.reasonCode())), "momentum named");
        assertFalse(keysOf(daily).contains(mid.get(3).id()), "the untouched, strongest skill waits");
        for (Plan.Item item : daily.items()) {
            Plan.Problem stretch = item.problems().get(item.problems().size() - 1);
            Integer ceiling = shownCeiling(skills, item.nodeKey());
            assertTrue(stretch.rating() > ceiling - 100, "the stretch is a step up: " + stretch.rating());
        }
    }

    // -- 4. strong ------------------------------------------------------------------------

    @Test
    @DisplayName("4. strong user ~2200: hard problems, no outgrown skills, progression with room to climb")
    void strong() {
        List<RoadmapTaxonomy.NodeDef> hard = nodes(5, d -> d.minRating() >= 1900 && d.maxRating() >= 2400);
        RoadmapTaxonomy.NodeDef outgrown = nodes(1, d -> d.maxRating() <= 1500).get(0);
        RoadmapTaxonomy.NodeDef cappedStrong = nodes(1, d -> d.maxRating() <= 1800 && d.minRating() >= 1300).get(0);
        List<PracticePlanner.Skill> skills = List.of(
            skill(hard.get(0), 45, 0, 30, 2150.0, 4, 2200),
            skill(hard.get(1), 55, 0, 30, 2200.0, 6, 2300),
            skill(hard.get(2), 85, 2, 60, 2200.0, 2, 2300),
            skill(hard.get(3), 35, 0, 20, 2200.0, 8, 2200),
            skill(hard.get(4), 40, 0, 20, 2150.0, 9, 2200),
            // Mastery reads low (few problems in it lately) but a 2200 player outgrew a <=1500 skill.
            skill(outgrown, 30, 0, 5, 2200.0, 40, 1500),
            // Strong and fresh, but the skill tops out below their level: nothing to step up to.
            skill(cappedStrong, 95, 0, 40, 2200.0, 1, 1800));

        var daily = planner.daily(in(skills, List.of()));
        var weekly = planner.weekly(in(skills, List.of()));

        sane(daily, skills);
        sane(weekly, skills);
        assertFalse(keysOf(daily).contains(outgrown.id()), "outgrown skill on the daily sheet");
        assertFalse(keysOf(weekly).contains(outgrown.id()));
        for (Plan.Item item : daily.items()) {
            for (Plan.Problem p : item.problems()) {
                if (Plan.CORE.equals(p.role())) assertTrue(p.rating() >= 1900, "core too easy: " + p.rating());
            }
        }
        Plan.Item progression = weekly.items().stream().filter(i -> "PROGRESSION".equals(i.reasonCode()))
            .findFirst().orElseThrow();
        assertEquals(hard.get(2).id(), progression.nodeKey(), "not the skill with no harder problems");
        assertTrue(progression.problems().get(progression.problems().size() - 1).rating() > 2350);
    }

    // -- 5. returning ---------------------------------------------------------------------

    @Test
    @DisplayName("5. back after months away: faded skills lead, labelled as revision")
    void returning() {
        List<RoadmapTaxonomy.NodeDef> d = nodes(5, n -> n.minRating() <= 1400 && n.maxRating() >= 1600);
        List<PracticePlanner.Skill> skills = new ArrayList<>();
        for (int i = 0; i < d.size(); i++) {
            double mastery = 60 + i * 5;
            double decay = ScoringFormulas.decay(mastery, 120);
            skills.add(skill(d.get(i), mastery, decay, 20, 1500.0, 120, 1600));
        }

        var daily = planner.daily(in(skills, List.of()));
        var weekly = planner.weekly(in(skills, List.of()));

        sane(daily, skills);
        assertTrue(daily.items().stream().allMatch(i -> "REVISION_DUE".equals(i.reasonCode())), "all revision");
        assertTrue(weekly.items().stream().filter(i -> !"PROGRESSION".equals(i.reasonCode()))
            .allMatch(i -> "REVISION".equals(i.priority())));
    }

    // -- 6. broad mastery, a few gaps -----------------------------------------------------

    @Test
    @DisplayName("6. strong almost everywhere: the few weak skills come first")
    void fewGaps() {
        List<RoadmapTaxonomy.NodeDef> d = nodes(10, n -> n.minRating() <= 1400 && n.maxRating() >= 1500);
        List<PracticePlanner.Skill> skills = new ArrayList<>();
        for (int i = 0; i < d.size(); i++) {
            boolean weak = i == 3 || i == 7;
            skills.add(skill(d.get(i), weak ? 20 : 85, 0, weak ? 6 : 40, weak ? 1250.0 : 1600.0, 20, weak ? 1200 : 1700));
        }

        var daily = planner.daily(in(skills, List.of()));

        sane(daily, skills);
        assertEquals(Set.of(d.get(3).id(), d.get(7).id()), new HashSet<>(keysOf(daily).subList(0, 2)));
        assertTrue(daily.items().subList(0, 2).stream().allMatch(i -> "WEAK_TOPIC".equals(i.reasonCode())));
    }

    // -- 7. sparse data -------------------------------------------------------------------

    @Test
    @DisplayName("7. sparse data: thin skills are not called weak, no rating falls back to the hardest solve")
    void sparse() {
        List<RoadmapTaxonomy.NodeDef> d = nodes(4, n -> n.minRating() <= 1000 && n.maxRating() >= 1200);
        List<PracticePlanner.Skill> skills = List.of(
            // Two attempts, nothing else known.
            new PracticePlanner.Skill(d.get(0).id(), 0, 0, 2, null, null, null),
            // Enough attempts, no skill rating, a hardest solve of 1000, no practice date.
            new PracticePlanner.Skill(d.get(1).id(), 30, 0, 5, null, null, 1000));

        var daily = planner.daily(in(skills, List.of(d.get(2).id(), d.get(3).id())));

        sane(daily, skills);
        assertFalse(keysOf(daily).contains(d.get(0).id()), "two attempts is not a measurement");
        assertEquals(d.get(1).id(), keysOf(daily).get(0), "the measured skill first");
        assertEquals(Plan.PARTIAL, daily.summary().basis());
        Plan.Item measured = daily.items().get(0);
        assertTrue(measured.targetRating() <= 1000 + ScoringFormulas.FIT_SKILL_STRETCH,
            "aim capped by the hardest solve: " + measured.targetRating());
    }

    // -- across all of them ---------------------------------------------------------------

    @Test
    @DisplayName("every persona's sheets are identical however the inputs are ordered")
    void deterministicAcrossPersonas() {
        List<RoadmapTaxonomy.NodeDef> d = nodes(8, n -> n.maxRating() - n.minRating() >= 300);
        Random rnd = new Random(3);
        List<PracticePlanner.Skill> skills = new ArrayList<>();
        for (RoadmapTaxonomy.NodeDef n : d) {
            skills.add(skill(n, rnd.nextInt(100), rnd.nextInt(40), 3 + rnd.nextInt(20),
                (double) (n.minRating() + rnd.nextInt(300)), rnd.nextInt(90), n.minRating() + 100));
        }
        List<PracticePlanner.Skill> shuffled = new ArrayList<>(skills);
        Collections.shuffle(shuffled, new Random(9));
        assertEquals(planner.daily(in(skills, List.of())), planner.daily(in(shuffled, List.of())));
        assertEquals(planner.weekly(in(skills, List.of())), planner.weekly(in(shuffled, List.of())));
        sane(planner.daily(in(skills, List.of())), skills);
        sane(planner.weekly(in(skills, List.of())), skills);
    }
}
