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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The planner's rules, each pinned against real skill-tree nodes and a problem list built from
 * their own rating bands, so nothing here depends on Codeforces or a database.
 */
class PracticePlannerTest {

    private static final Instant NOW = Instant.parse("2026-10-22T09:00:00Z");

    /** Every node gets a problem every 100 points across its band, keyed to that node. */
    private static List<ProblemRecommender.Recommended> problemsFor(RoadmapTaxonomy.NodeDef def,
                                                                    Set<String> solved) {
        int contestId = 1000 + RoadmapTaxonomy.NODES.indexOf(def);
        List<ProblemRecommender.Recommended> out = new ArrayList<>();
        for (int r = def.minRating(); r <= def.maxRating(); r += 100) {
            String index = "P" + r;
            out.add(new ProblemRecommender.Recommended(contestId, index, def.id() + " " + r, r,
                List.of(), solved.contains(contestId + index), 50.0,
                ProblemRecommender.practicePath(contestId, index, def.id()),
                ProblemRecommender.judgeUrl(contestId, index)));
        }
        return out;
    }

    private final PracticePlanner planner = new PracticePlanner(
        (def, skillRating, mastery, solved) -> problemsFor(def, solved));

    /** {@code n} nodes from {@code n} different topics, each with a band wide enough to climb. */
    private static List<RoadmapTaxonomy.NodeDef> distinctTopics(int n) {
        Map<String, RoadmapTaxonomy.NodeDef> byTopic = new LinkedHashMap<>();
        for (RoadmapTaxonomy.NodeDef d : RoadmapTaxonomy.NODES) {
            if (d.maxRating() - d.minRating() >= 400) byTopic.putIfAbsent(d.rollupTopic(), d);
        }
        return byTopic.values().stream().limit(n).toList();
    }

    /** A topic holding at least {@code n} climbable nodes, and those nodes. */
    private static List<RoadmapTaxonomy.NodeDef> sameTopic(int n) {
        return RoadmapTaxonomy.NODES.stream()
            .filter(d -> d.maxRating() - d.minRating() >= 400)
            .collect(Collectors.groupingBy(RoadmapTaxonomy.NodeDef::rollupTopic, LinkedHashMap::new,
                Collectors.toList()))
            .values().stream().filter(l -> l.size() >= n).findFirst().orElseThrow()
            .subList(0, n);
    }

    private static PracticePlanner.Skill skill(RoadmapTaxonomy.NodeDef d, double mastery, double decay,
                                               int attempted, Instant last) {
        return new PracticePlanner.Skill(d.id(), mastery, decay, attempted, null, last);
    }

    private static PracticePlanner.Inputs inputs(List<PracticePlanner.Skill> skills, List<String> roadmap,
                                                 Set<String> solved, Set<String> exclude) {
        return new PracticePlanner.Inputs(skills, roadmap, solved, exclude, true, NOW);
    }

    private static Instant daysAgo(int days) {
        return NOW.minus(Duration.ofDays(days));
    }

    private static List<String> keys(PracticePlanner.Sheet sheet) {
        return sheet.items().stream().flatMap(i -> i.problems().stream()).map(Plan.Problem::key).toList();
    }

    // -- ranking and reasons ---------------------------------------------------

    @Test
    @DisplayName("Daily sheet ranks weak skills first and says why")
    void dailyRanksWeakestFirst() {
        List<RoadmapTaxonomy.NodeDef> d = distinctTopics(4);
        var sheet = planner.daily(inputs(List.of(
            skill(d.get(0), 80, 0, 10, daysAgo(40)),
            skill(d.get(1), 15, 0, 6, daysAgo(40)),
            skill(d.get(2), 40, 0, 6, daysAgo(40)),
            skill(d.get(3), 60, 0, 6, daysAgo(40))), List.of(), Set.of(), Set.of()));

        assertEquals(List.of(d.get(1).id(), d.get(2).id(), d.get(3).id()),
            sheet.items().stream().map(Plan.Item::nodeKey).toList());
        assertTrue(sheet.items().stream().allMatch(i -> "WEAK_TOPIC".equals(i.reasonCode())));
        assertTrue(sheet.items().get(0).reason().contains("15%"));
        assertEquals(Plan.MEASURED, sheet.summary().basis());
        assertTrue(sheet.summary().notes().isEmpty());
    }

    @Test
    @DisplayName("A strong skill that has faded is put on the daily sheet as revision")
    void fadedSkillIsRevision() {
        List<RoadmapTaxonomy.NodeDef> d = distinctTopics(2);
        var sheet = planner.daily(inputs(List.of(
            skill(d.get(0), 90, 70, 20, daysAgo(60)),
            skill(d.get(1), 95, 0, 20, daysAgo(60))), List.of(), Set.of(), Set.of()));

        Plan.Item first = sheet.items().get(0);
        assertEquals(d.get(0).id(), first.nodeKey());
        assertEquals("REVISION_DUE", first.reasonCode());
        assertTrue(first.reason().contains("70%"));
    }

    @Test
    @DisplayName("A skill practised today outranks an equally strong untouched one, as momentum")
    void momentumCounts() {
        List<RoadmapTaxonomy.NodeDef> d = distinctTopics(2);
        var sheet = planner.daily(inputs(List.of(
            skill(d.get(0), 80, 0, 10, daysAgo(60)),
            skill(d.get(1), 80, 0, 10, NOW)), List.of(), Set.of(), Set.of()));

        assertEquals(d.get(1).id(), sheet.items().get(0).nodeKey());
        assertEquals("MOMENTUM", sheet.items().get(0).reasonCode());
        assertTrue(sheet.items().get(0).reason().contains("today"));
    }

    @Test
    @DisplayName("Daily sheet takes one skill per topic before a second from the same topic")
    void dailySpreadsTopics() {
        List<RoadmapTaxonomy.NodeDef> same = sameTopic(3);
        RoadmapTaxonomy.NodeDef other = distinctTopics(40).stream()
            .filter(n -> !n.rollupTopic().equals(same.get(0).rollupTopic())).findFirst().orElseThrow();

        var sheet = planner.daily(inputs(List.of(
            skill(same.get(0), 5, 0, 5, daysAgo(40)),
            skill(same.get(1), 6, 0, 5, daysAgo(40)),
            skill(same.get(2), 7, 0, 5, daysAgo(40)),
            skill(other, 60, 0, 5, daysAgo(40))), List.of(), Set.of(), Set.of()));

        List<String> picked = sheet.items().stream().map(Plan.Item::nodeKey).toList();
        assertEquals(3, picked.size());
        assertTrue(picked.contains(other.id()), "the other topic gets a place: " + picked);
        assertEquals(same.get(0).id(), picked.get(0));
    }

    // -- problems --------------------------------------------------------------

    @Test
    @DisplayName("Problems climb from warm-up to stretch around the skill-rating target")
    void problemsClimb() {
        RoadmapTaxonomy.NodeDef d = distinctTopics(1).get(0);
        double skillRating = d.minRating() + 100;
        var sheet = planner.daily(inputs(List.of(
            new PracticePlanner.Skill(d.id(), 30, 0, 8, skillRating, daysAgo(30))),
            List.of(), Set.of(), Set.of()));

        Plan.Item item = sheet.items().get(0);
        int target = (int) Math.min(d.maxRating(), skillRating + ScoringFormulas.FIT_SKILL_STRETCH);
        assertEquals(target, item.targetRating());
        assertEquals(List.of(Plan.WARM_UP, Plan.CORE, Plan.CORE, Plan.STRETCH),
            item.problems().stream().map(Plan.Problem::role).toList());
        List<Integer> ratings = item.problems().stream().map(Plan.Problem::rating).toList();
        List<Integer> sorted = new ArrayList<>(ratings);
        Collections.sort(sorted);
        assertEquals(sorted, ratings, "easiest first");
        assertTrue(ratings.stream().allMatch(r -> r >= d.minRating() && r <= d.maxRating()));
    }

    @Test
    @DisplayName("Without a skill rating the target comes from where mastery sits in the band")
    void targetFromMastery() {
        RoadmapTaxonomy.NodeDef d = distinctTopics(1).get(0);
        int low = PracticePlanner.targetFor(d, new PracticePlanner.Skill(d.id(), 0, 0, 5, null, null));
        int high = PracticePlanner.targetFor(d, new PracticePlanner.Skill(d.id(), 100, 0, 5, null, null));
        assertTrue(low < high);
        assertTrue(low >= d.minRating() && high <= d.maxRating());
    }

    @Test
    @DisplayName("Solved problems are never offered as new practice")
    void solvedAreSkipped() {
        RoadmapTaxonomy.NodeDef d = distinctTopics(1).get(0);
        Set<String> solved = new HashSet<>();
        for (var p : problemsFor(d, Set.of())) {
            if (p.rating() <= d.minRating() + 200) solved.add(p.contestId() + p.index());
        }
        var sheet = planner.daily(inputs(List.of(skill(d, 10, 0, 5, daysAgo(30))),
            List.of(), solved, Set.of()));

        assertFalse(sheet.items().get(0).problems().isEmpty());
        for (String key : keys(sheet)) assertFalse(solved.contains(key), key + " was solved");
        assertTrue(sheet.items().get(0).problems().stream().noneMatch(Plan.Problem::solved));
    }

    @Test
    @DisplayName("No problem appears twice on a sheet, even when skills share problems")
    void noDuplicatesWithinSheet() {
        List<RoadmapTaxonomy.NodeDef> d = distinctTopics(3);
        RoadmapTaxonomy.NodeDef shared = d.get(0);
        PracticePlanner sharing = new PracticePlanner(
            (def, sr, m, solved) -> problemsFor(shared, solved));

        var sheet = sharing.daily(inputs(List.of(
            skill(d.get(0), 10, 0, 5, daysAgo(30)),
            skill(d.get(1), 20, 0, 5, daysAgo(30)),
            skill(d.get(2), 30, 0, 5, daysAgo(30))), List.of(), Set.of(), Set.of()));

        List<String> all = keys(sheet);
        assertEquals(new HashSet<>(all).size(), all.size(), "duplicates in " + all);
        assertTrue(all.size() > 4, "later skills still got problems");
    }

    @Test
    @DisplayName("Problems already on another live sheet are left off")
    void excludesOtherSheets() {
        RoadmapTaxonomy.NodeDef d = distinctTopics(1).get(0);
        var first = planner.daily(inputs(List.of(skill(d, 10, 0, 5, daysAgo(30))),
            List.of(), Set.of(), Set.of()));
        Set<String> onDaily = new HashSet<>(keys(first));

        var weekly = planner.weekly(inputs(List.of(skill(d, 10, 0, 5, daysAgo(30))),
            List.of(), Set.of(), onDaily));
        for (String key : keys(weekly)) assertFalse(onDaily.contains(key), key + " is on the daily sheet");
    }

    @Test
    @DisplayName("When the problem list is unavailable, skills are still named and the sheet says why")
    void problemsetUnavailable() {
        RoadmapTaxonomy.NodeDef d = distinctTopics(1).get(0);
        PracticePlanner empty = new PracticePlanner((def, sr, m, solved) -> List.of());
        var sheet = empty.daily(new PracticePlanner.Inputs(List.of(skill(d, 10, 0, 5, daysAgo(30))),
            List.of(), Set.of(), Set.of(), false, NOW));

        assertEquals(1, sheet.items().size());
        assertTrue(sheet.items().get(0).problems().isEmpty());
        assertTrue(sheet.summary().notes().stream().anyMatch(n -> n.contains("could not be loaded")));
    }

    // -- sparse data -----------------------------------------------------------

    @Test
    @DisplayName("A new user gets the roadmap's next skills, marked as inferred")
    void newUserFallsBackToRoadmap() {
        List<RoadmapTaxonomy.NodeDef> d = distinctTopics(4);
        var sheet = planner.daily(inputs(List.of(),
            List.of(d.get(2).id(), d.get(0).id(), d.get(1).id(), d.get(3).id()), Set.of(), Set.of()));

        assertEquals(List.of(d.get(2).id(), d.get(0).id(), d.get(1).id()),
            sheet.items().stream().map(Plan.Item::nodeKey).toList(), "roadmap order kept");
        assertTrue(sheet.items().stream().allMatch(i ->
            Plan.INFERRED.equals(i.basis()) && "NEXT_ON_ROADMAP".equals(i.reasonCode())));
        assertEquals(Plan.INFERRED, sheet.summary().basis());
        assertFalse(sheet.summary().notes().isEmpty());
    }

    @Test
    @DisplayName("Skills with too few attempts are not ranked as weak; measured ones come first")
    void sparseSkillsAreNotWeak() {
        List<RoadmapTaxonomy.NodeDef> d = distinctTopics(3);
        var sheet = planner.daily(inputs(List.of(
            skill(d.get(0), 0, 0, 2, daysAgo(5)),
            skill(d.get(1), 70, 0, 9, daysAgo(40))),
            List.of(d.get(2).id()), Set.of(), Set.of()));

        List<String> picked = sheet.items().stream().map(Plan.Item::nodeKey).toList();
        assertEquals(List.of(d.get(1).id(), d.get(2).id()), picked);
        assertEquals(Plan.PARTIAL, sheet.summary().basis());
    }

    @Test
    @DisplayName("No history and no roadmap gives an empty sheet that says what to do")
    void nothingAtAll() {
        var sheet = planner.daily(inputs(List.of(), List.of(), Set.of(), Set.of()));
        assertTrue(sheet.items().isEmpty());
        assertEquals(Plan.NONE, sheet.summary().basis());
        assertTrue(sheet.summary().notes().get(0).contains("sync"));
    }

    @Test
    @DisplayName("Unknown skill keys are ignored rather than failing")
    void unknownKeysIgnored() {
        var sheet = planner.daily(inputs(List.of(
            new PracticePlanner.Skill("no-such-skill", 5, 0, 10, null, null)),
            List.of("also-missing"), Set.of(), Set.of()));
        assertTrue(sheet.items().isEmpty());
    }

    // -- determinism -------------------------------------------------------------

    @Test
    @DisplayName("Identical inputs give an identical sheet, whatever order they arrive in")
    void deterministic() {
        List<RoadmapTaxonomy.NodeDef> d = distinctTopics(6);
        List<PracticePlanner.Skill> skills = new ArrayList<>();
        for (int i = 0; i < d.size(); i++) skills.add(skill(d.get(i), 20 + i * 5, i * 3, 6, daysAgo(10 + i)));

        PracticePlanner shuffling = new PracticePlanner((def, sr, m, solved) -> {
            List<ProblemRecommender.Recommended> list = new ArrayList<>(problemsFor(def, solved));
            Collections.shuffle(list, new Random(def.id().hashCode()));
            return list;
        });
        List<PracticePlanner.Skill> reversed = new ArrayList<>(skills);
        Collections.reverse(reversed);

        var a = planner.weekly(inputs(skills, List.of(), Set.of(), Set.of()));
        var b = shuffling.weekly(inputs(reversed, List.of(), Set.of(), Set.of()));
        assertEquals(a, b);
        assertEquals(planner.daily(inputs(skills, List.of(), Set.of(), Set.of())),
            shuffling.daily(inputs(reversed, List.of(), Set.of(), Set.of())));
    }

    // -- weekly ------------------------------------------------------------------

    @Test
    @DisplayName("Weekly plan: retained-weakest skills as numbered sessions, a progression slot, a goal")
    void weeklyPlan() {
        List<RoadmapTaxonomy.NodeDef> d = distinctTopics(6);
        var sheet = planner.weekly(inputs(List.of(
            skill(d.get(0), 90, 60, 20, daysAgo(80)),   // faded: retains 30
            skill(d.get(1), 20, 0, 6, daysAgo(3)),       // weak: retains 20
            skill(d.get(2), 45, 0, 6, daysAgo(3)),
            skill(d.get(3), 50, 0, 6, daysAgo(3)),
            skill(d.get(4), 85, 2, 30, daysAgo(2)),      // strong and fresh
            skill(d.get(5), 70, 0, 9, daysAgo(2))), List.of(), Set.of(), Set.of()));

        List<Plan.Item> items = sheet.items();
        assertEquals(5, items.size());
        assertEquals(List.of(d.get(1).id(), d.get(0).id(), d.get(2).id(), d.get(3).id(), d.get(4).id()),
            items.stream().map(Plan.Item::nodeKey).toList());
        assertEquals(List.of(1, 2, 3, 4, 5), items.stream().map(Plan.Item::session).toList());
        assertEquals("REVISION", items.get(1).priority());
        assertEquals("NEW", items.get(0).priority());

        Plan.Item progression = items.get(4);
        assertEquals("PROGRESSION", progression.reasonCode());
        assertEquals(List.of(Plan.CORE, Plan.STRETCH, Plan.STRETCH),
            progression.problems().stream().map(Plan.Problem::role).toList());

        int total = items.stream().mapToInt(i -> i.problems().size()).sum();
        assertEquals((int) Math.ceil(total * 2.0 / 3.0), sheet.summary().goalProblems());
    }

    @Test
    @DisplayName("Weekly plan has no progression slot when no strong fresh skill exists")
    void weeklyWithoutProgression() {
        List<RoadmapTaxonomy.NodeDef> d = distinctTopics(2);
        var sheet = planner.weekly(inputs(List.of(
            skill(d.get(0), 30, 0, 6, daysAgo(3)),
            skill(d.get(1), 40, 25, 6, daysAgo(60))), List.of(), Set.of(), Set.of()));
        assertTrue(sheet.items().stream().noneMatch(i -> "PROGRESSION".equals(i.reasonCode())));
    }

    // -- contest preparation -------------------------------------------------------

    @Test
    @DisplayName("Contest prep aims a warm-up, core and stretch around the rating, in skills that reach it")
    void contestPrep() {
        RoadmapTaxonomy.NodeDef low = RoadmapTaxonomy.NODES.stream()
            .min(java.util.Comparator.comparingInt(RoadmapTaxonomy.NodeDef::maxRating)).orElseThrow();
        RoadmapTaxonomy.NodeDef high = RoadmapTaxonomy.NODES.stream()
            .filter(n -> n.minRating() > low.maxRating() + 2 * PracticePlanner.CONTEST_BAND_REACH)
            .filter(n -> n.maxRating() - n.minRating() >= 400)
            .findFirst().orElseThrow();
        int rating = high.minRating() + 200;

        var contest = new Plan.Contest(2001, "Codeforces Round (Div. 2)", "Div. 2",
            NOW.plus(Duration.ofDays(2)), 7200L, "https://codeforces.com/contests/2001");
        var sheet = planner.contestPrep(inputs(List.of(
            skill(low, 5, 0, 10, daysAgo(10)),
            skill(high, 40, 0, 10, daysAgo(10))), List.of(), Set.of(), Set.of()),
            rating, Plan.MEASURED, contest, new Plan.ContestRecord(4, 2.25, -12.5));

        assertEquals(List.of(high.id()), sheet.items().stream().map(Plan.Item::nodeKey).toList(),
            "a skill far below the rating is not contest preparation");
        Plan.Item item = sheet.items().get(0);
        assertEquals(rating, item.targetRating());
        assertEquals(List.of(Plan.WARM_UP, Plan.CORE, Plan.STRETCH),
            item.problems().stream().map(Plan.Problem::role).toList());
        assertEquals(rating, sheet.summary().rating());
        assertEquals(contest, sheet.summary().contest());
        assertTrue(sheet.summary().notes().stream().anyMatch(n -> n.contains("2.3 problems")));
    }

    @Test
    @DisplayName("Contest prep without a rating or an upcoming round says so")
    void contestPrepInferred() {
        RoadmapTaxonomy.NodeDef d = distinctTopics(1).get(0);
        var sheet = planner.contestPrep(inputs(List.of(skill(d, 30, 0, 5, daysAgo(4))),
            List.of(), Set.of(), Set.of()), d.minRating() + 100, Plan.INFERRED, null, null);

        assertNull(sheet.summary().contest());
        assertEquals(Plan.INFERRED, sheet.summary().ratingBasis());
        assertTrue(sheet.summary().notes().stream().anyMatch(n -> n.contains("estimated")));
        assertTrue(sheet.summary().notes().stream().anyMatch(n -> n.contains("general preparation")));
    }

    // -- revision ------------------------------------------------------------------

    @Test
    @DisplayName("Revision re-solves the problems solved longest ago, then one new problem")
    void revisionProblems() {
        RoadmapTaxonomy.NodeDef d = distinctTopics(1).get(0);
        List<ProblemRecommender.Recommended> all = problemsFor(d, Set.of());
        String oldest = all.get(2).contestId() + all.get(2).index();
        String middle = all.get(0).contestId() + all.get(0).index();
        String newest = all.get(1).contestId() + all.get(1).index();
        Set<String> solved = Set.of(oldest, middle, newest);

        List<Plan.Problem> out = planner.revision(d.id(), skill(d, 60, 30, 10, daysAgo(70)), solved,
            Map.of(oldest, daysAgo(200), middle, daysAgo(100), newest, daysAgo(80)));

        assertEquals(3, out.size());
        assertEquals(List.of(oldest, middle), out.subList(0, 2).stream().map(Plan.Problem::key).toList());
        assertTrue(out.subList(0, 2).stream().allMatch(p -> Plan.RESOLVE.equals(p.role()) && p.solved()));
        Plan.Problem fresh = out.get(2);
        assertEquals(Plan.CORE, fresh.role());
        assertFalse(solved.contains(fresh.key()));
        assertNotEquals(oldest, fresh.key());
    }

    @Test
    @DisplayName("Revision of an unknown skill is empty")
    void revisionUnknown() {
        assertTrue(planner.revision("nope", null, Set.of(), Map.of()).isEmpty());
    }
}
