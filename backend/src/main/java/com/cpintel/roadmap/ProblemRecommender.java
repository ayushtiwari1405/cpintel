package com.cpintel.roadmap;

import com.cpintel.analytics.ScoringFormulas;
import com.cpintel.integration.codeforces.CfModels;
import com.cpintel.integration.codeforces.CfProblemsetClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Picks the Codeforces problems to put in front of a user for a given skill.
 *
 * <p>One place does this now because two callers needed it and only one had it. The roadmap
 * filtered the whole problemset itself, and the daily and weekly sheets carried no problems at
 * all - they listed topic names and a "target difficulty" of around 250, which is not a
 * Codeforces rating and never was. A sheet that recommends practice without naming anything to
 * practise is not a recommendation.
 *
 * <h2>Why the index</h2>
 *
 * <p>The roadmap used to stream the entire problemset once per node. That was tolerable at
 * thirty-five nodes and is not at a hundred and forty: ten thousand problems scanned a hundred
 * and forty times, with a nested tag comparison inside, on every render of the page. The
 * problemset is bucketed by tag once per snapshot and each node then reads only the buckets it
 * asked for.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ProblemRecommender {

    private final CfProblemsetClient problemsetClient;

    /** Rebuilt whenever the underlying problemset snapshot changes identity. */
    private volatile Index index;

    /**
     * One problem, ready to render.
     *
     * @param practicePath in-app route that opens this problem in the practice workspace
     * @param judgeUrl     the problem on codeforces.com, for anyone who wants the original
     * @param fit          how well it suits the user right now, 0-100
     */
    public record Recommended(
        Integer contestId,
        String index,
        String name,
        Integer rating,
        List<String> tags,
        boolean solved,
        double fit,
        String practicePath,
        String judgeUrl
    ) {}

    /**
     * The best problems for one node, given how far along the user is in it.
     *
     * <p>Unsolved problems come first - the point is what to do next, not what is already
     * done - and within that, by how well the difficulty suits the user's current level in this
     * node. Solved ones are kept at the tail rather than dropped so the panel can show progress.
     */
    public List<Recommended> forNode(RoadmapTaxonomy.NodeDef def, double masteryScore,
                                     Set<String> solvedKeys, int limit) {
        if (def == null) return List.of();

        List<CfModels.Submission.Problem> candidates = candidatesFor(def);
        if (candidates.isEmpty()) return List.of();

        Set<String> solved = solvedKeys == null ? Set.of() : solvedKeys;
        List<Recommended> scored = new ArrayList<>(candidates.size());

        for (CfModels.Submission.Problem p : candidates) {
            if (p.getContestId() == null || p.getIndex() == null) continue;
            String key = p.getContestId() + p.getIndex();
            scored.add(new Recommended(
                p.getContestId(),
                p.getIndex(),
                p.getName(),
                p.getRating(),
                p.getTags() == null ? List.of() : p.getTags(),
                solved.contains(key),
                ScoringFormulas.scoreProblemFit(
                    masteryScore, def.minRating(), def.maxRating(), p.getRating()),
                practicePath(p.getContestId(), p.getIndex(), def.id()),
                judgeUrl(p.getContestId(), p.getIndex())));
        }

        scored.sort(Comparator.comparing(Recommended::solved)
            .thenComparing(Comparator.comparingDouble(Recommended::fit).reversed())
            .thenComparing(r -> r.rating() == null ? Integer.MAX_VALUE : r.rating()));

        return scored.size() <= limit ? scored : new ArrayList<>(scored.subList(0, limit));
    }

    /**
     * The in-app route that opens a problem in the practice workspace.
     *
     * <p>Everything used to link straight out to codeforces.com in a new tab, which walked the
     * user out of the product at precisely the moment they had decided to practise - past the
     * editor, the local runner and the sample-test console that exist for this. The node id
     * rides along so the workspace can show what skill the problem was suggested for.
     */
    public static String practicePath(Integer contestId, String problemIndex, String nodeId) {
        if (contestId == null || problemIndex == null) return null;
        StringBuilder path = new StringBuilder("/practice?platform=CODEFORCES")
            .append("&contest=").append(contestId)
            .append("&index=").append(encode(problemIndex));
        if (nodeId != null && !nodeId.isBlank()) {
            path.append("&node=").append(encode(nodeId));
        }
        return path.toString();
    }

    public static String judgeUrl(Integer contestId, String problemIndex) {
        if (contestId == null || problemIndex == null) return null;
        return "https://codeforces.com/problemset/problem/" + contestId + "/" + problemIndex;
    }

    /** True when the problemset snapshot is empty, so callers can say so rather than show nothing. */
    public boolean problemsetAvailable() {
        return !problemsetClient.getAllProblems().isEmpty();
    }

    // -- indexing ----------------------------------------------------------

    private List<CfModels.Submission.Problem> candidatesFor(RoadmapTaxonomy.NodeDef def) {
        Index snapshot = currentIndex();
        if (snapshot == null) return List.of();

        // Start from the smallest bucket the node names, then apply the node's full rule. Any
        // matching problem must carry at least one of anyTags (or, when anyTags is empty, all of
        // allTags), so a bucket lookup can never miss one.
        List<String> lookupTags = def.anyTags().isEmpty() ? def.allTags() : def.anyTags();
        if (lookupTags.isEmpty()) return List.of();

        Set<CfModels.Submission.Problem> pool = new LinkedHashSet<>();
        for (String tag : lookupTags) {
            pool.addAll(snapshot.byTag.getOrDefault(tag, List.of()));
        }

        List<CfModels.Submission.Problem> matched = new ArrayList<>();
        for (CfModels.Submission.Problem p : pool) {
            if (def.matches(p.getRating(), lowercaseTags(p))) matched.add(p);
        }
        return matched;
    }

    private Index currentIndex() {
        List<CfModels.Submission.Problem> problems = problemsetClient.getAllProblems();
        if (problems.isEmpty()) return null;

        Index snapshot = index;
        // Identity comparison is deliberate: the client swaps in a whole new immutable list when
        // it refreshes, so a changed reference is exactly the signal that the snapshot moved on.
        if (snapshot != null && snapshot.source == problems) return snapshot;

        synchronized (this) {
            snapshot = index;
            if (snapshot != null && snapshot.source == problems) return snapshot;
            snapshot = Index.build(problems);
            index = snapshot;
            log.info("Indexed {} Codeforces problems across {} tags",
                problems.size(), snapshot.byTag.size());
            return snapshot;
        }
    }

    private static Set<String> lowercaseTags(CfModels.Submission.Problem p) {
        return PlatformTagVocabulary.forCodeforces(p.getTags());
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    /** The problemset bucketed by tag, tied to the snapshot it was built from. */
    private static final class Index {
        private final List<CfModels.Submission.Problem> source;
        private final Map<String, List<CfModels.Submission.Problem>> byTag;

        private Index(List<CfModels.Submission.Problem> source,
                      Map<String, List<CfModels.Submission.Problem>> byTag) {
            this.source = source;
            this.byTag = byTag;
        }

        static Index build(List<CfModels.Submission.Problem> problems) {
            Map<String, List<CfModels.Submission.Problem>> byTag = new LinkedHashMap<>();
            for (CfModels.Submission.Problem p : problems) {
                if (p.getTags() == null) continue;
                for (String tag : p.getTags()) {
                    if (tag == null) continue;
                    byTag.computeIfAbsent(tag.trim().toLowerCase(Locale.ROOT), k -> new ArrayList<>())
                        .add(p);
                }
            }
            return new Index(problems, byTag);
        }
    }
}
