package com.cpintel.roadmap;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Translates each judge's tag vocabulary into the Codeforces one, which the skill tree is
 * written against.
 *
 * <p>Topic mastery used to read {@code CfSubmissionRepository} and nothing else, so a user
 * whose LeetCode and CodeChef accounts were linked and synced saw none of that work reflected
 * anywhere. The submissions were being fetched and stored the whole time; only the analytics
 * pass ignored them.
 *
 * <p>Codeforces is the target vocabulary rather than some neutral third one because the tree's
 * bands and tags are already expressed in it, and because it is the richest of the three —
 * mapping into it loses the least. Tags with no sensible Codeforces equivalent are dropped
 * rather than guessed at; a wrong tag is worse than a missing one, because it silently credits
 * a skill the user has not shown.
 */
public final class PlatformTagVocabulary {

    private PlatformTagVocabulary() {}

    /**
     * LeetCode publishes no contest-style difficulty rating on a problem, only Easy / Medium /
     * Hard. These are the Codeforces ratings those bands sit closest to in practice, and they
     * are used for band matching exactly as a real rating would be. That is an approximation,
     * and it is the reason a LeetCode-only user's node bands are coarser than a Codeforces
     * user's: three distinct values cannot separate a 1700 node from a 1900 one.
     */
    public static final int LC_EASY_RATING   = 1200;
    public static final int LC_MEDIUM_RATING = 1600;
    public static final int LC_HARD_RATING   = 2100;

    /** LeetCode slugs to Codeforces tags. */
    private static final Map<String, List<String>> LEETCODE = Map.ofEntries(
        Map.entry("array",                      List.of("implementation")),
        Map.entry("string",                     List.of("strings")),
        Map.entry("hash-table",                 List.of("data structures")),
        Map.entry("dynamic-programming",        List.of("dp")),
        Map.entry("math",                       List.of("math")),
        Map.entry("sorting",                    List.of("sortings")),
        Map.entry("greedy",                     List.of("greedy")),
        Map.entry("depth-first-search",         List.of("dfs and similar")),
        Map.entry("breadth-first-search",       List.of("graphs", "shortest paths")),
        Map.entry("binary-search",              List.of("binary search")),
        Map.entry("tree",                       List.of("trees")),
        Map.entry("binary-tree",                List.of("trees")),
        Map.entry("matrix",                     List.of("implementation")),
        Map.entry("two-pointers",               List.of("two pointers")),
        Map.entry("bit-manipulation",           List.of("bitmasks")),
        Map.entry("stack",                      List.of("data structures")),
        Map.entry("heap-priority-queue",        List.of("data structures", "greedy")),
        Map.entry("graph",                      List.of("graphs")),
        Map.entry("prefix-sum",                 List.of("implementation")),
        Map.entry("simulation",                 List.of("implementation")),
        Map.entry("counting",                   List.of("combinatorics")),
        Map.entry("sliding-window",             List.of("two pointers")),
        Map.entry("union-find",                 List.of("dsu")),
        Map.entry("linked-list",                List.of("data structures")),
        Map.entry("monotonic-stack",            List.of("data structures")),
        Map.entry("trie",                       List.of("data structures")),
        Map.entry("divide-and-conquer",         List.of("divide and conquer")),
        Map.entry("bitmask",                    List.of("bitmasks")),
        Map.entry("queue",                      List.of("data structures")),
        Map.entry("recursion",                  List.of("dfs and similar")),
        Map.entry("combinatorics",              List.of("combinatorics")),
        Map.entry("number-theory",              List.of("number theory")),
        Map.entry("geometry",                   List.of("geometry")),
        Map.entry("game-theory",                List.of("games")),
        Map.entry("topological-sort",           List.of("graphs", "dfs and similar")),
        Map.entry("shortest-path",              List.of("shortest paths")),
        Map.entry("segment-tree",               List.of("data structures")),
        Map.entry("binary-indexed-tree",        List.of("data structures")),
        Map.entry("probability-and-statistics", List.of("probabilities")),
        Map.entry("interactive",                List.of("interactive")),
        Map.entry("randomized",                 List.of("probabilities")),
        Map.entry("string-matching",            List.of("strings", "string suffix structures")),
        Map.entry("suffix-array",               List.of("string suffix structures")),
        Map.entry("enumeration",                List.of("brute force")),
        Map.entry("backtracking",               List.of("brute force", "dfs and similar")),
        Map.entry("memoization",                List.of("dp")),
        Map.entry("monotonic-queue",            List.of("data structures", "two pointers")),
        Map.entry("shortest-paths",             List.of("shortest paths")),
        Map.entry("strongly-connected-component", List.of("graphs", "dfs and similar"))
    );

    /**
     * CodeChef tags. Its tag set is editorially assigned and far looser than the other two, so
     * this covers the ones that appear often enough to be worth trusting.
     */
    private static final Map<String, List<String>> CODECHEF = Map.ofEntries(
        Map.entry("dp",                 List.of("dp")),
        Map.entry("dynamic-programming",List.of("dp")),
        Map.entry("greedy",             List.of("greedy")),
        Map.entry("math",               List.of("math")),
        Map.entry("number-theory",      List.of("number theory")),
        Map.entry("graphs",             List.of("graphs")),
        Map.entry("graph",              List.of("graphs")),
        Map.entry("dfs",                List.of("dfs and similar")),
        Map.entry("bfs",                List.of("graphs", "shortest paths")),
        Map.entry("trees",              List.of("trees")),
        Map.entry("tree",               List.of("trees")),
        Map.entry("sorting",            List.of("sortings")),
        Map.entry("binary-search",      List.of("binary search")),
        Map.entry("binarysearch",       List.of("binary search")),
        Map.entry("two-pointers",       List.of("two pointers")),
        Map.entry("strings",            List.of("strings")),
        Map.entry("string",             List.of("strings")),
        Map.entry("implementation",     List.of("implementation")),
        Map.entry("adhoc",              List.of("implementation")),
        Map.entry("simulation",         List.of("implementation")),
        Map.entry("brute-force",        List.of("brute force")),
        Map.entry("bitmask",            List.of("bitmasks")),
        Map.entry("bit-manipulation",   List.of("bitmasks")),
        Map.entry("segment-tree",       List.of("data structures")),
        Map.entry("segtree",            List.of("data structures")),
        Map.entry("fenwick",            List.of("data structures")),
        Map.entry("bit",                List.of("data structures")),
        Map.entry("data-structures",    List.of("data structures")),
        Map.entry("dsu",                List.of("dsu")),
        Map.entry("union-find",         List.of("dsu")),
        Map.entry("combinatorics",      List.of("combinatorics")),
        Map.entry("probability",        List.of("probabilities")),
        Map.entry("expected-value",     List.of("probabilities")),
        Map.entry("geometry",           List.of("geometry")),
        Map.entry("game-theory",        List.of("games")),
        Map.entry("games",              List.of("games")),
        Map.entry("matrix-exponentiation", List.of("matrices")),
        Map.entry("matrices",           List.of("matrices")),
        Map.entry("fft",                List.of("fft")),
        Map.entry("flows",              List.of("flows")),
        Map.entry("maxflow",            List.of("flows")),
        Map.entry("shortest-path",      List.of("shortest paths")),
        Map.entry("dijkstra",           List.of("shortest paths")),
        Map.entry("modular-arithmetic", List.of("number theory")),
        Map.entry("sieve",              List.of("number theory")),
        Map.entry("primes",             List.of("number theory")),
        Map.entry("constructive",       List.of("constructive algorithms")),
        Map.entry("interactive",        List.of("interactive")),
        Map.entry("hashing",            List.of("hashing")),
        Map.entry("trie",               List.of("data structures")),
        Map.entry("divide-and-conquer", List.of("divide and conquer")),
        Map.entry("lca",                List.of("trees")),
        Map.entry("scc",                List.of("graphs", "dfs and similar"))
    );

    /**
     * Codeforces tags pass through unchanged apart from case and spacing; the tree is written
     * in this vocabulary, so there is nothing to translate.
     */
    public static Set<String> forCodeforces(List<String> tags) {
        Set<String> out = new LinkedHashSet<>();
        if (tags == null) return out;
        for (String tag : tags) {
            String normalised = clean(tag);
            if (!normalised.isEmpty()) out.add(normalised);
        }
        return out;
    }

    public static Set<String> forLeetCode(List<String> tags) {
        return translate(tags, LEETCODE);
    }

    public static Set<String> forCodeChef(List<String> tags) {
        return translate(tags, CODECHEF);
    }

    /** Dispatch by the platform name used on {@code PlatformAccount}. */
    public static Set<String> forPlatform(String platform, List<String> tags) {
        if (platform == null) return Set.of();
        return switch (platform.toUpperCase(Locale.ROOT)) {
            case "CODEFORCES" -> forCodeforces(tags);
            case "LEETCODE"   -> forLeetCode(tags);
            case "CODECHEF"   -> forCodeChef(tags);
            default           -> Set.of();
        };
    }

    /** LeetCode's Easy/Medium/Hard as an approximate Codeforces rating, or null if unknown. */
    public static Integer leetcodeDifficultyRating(String difficulty) {
        if (difficulty == null) return null;
        return switch (difficulty.trim().toUpperCase(Locale.ROOT)) {
            case "EASY"   -> LC_EASY_RATING;
            case "MEDIUM" -> LC_MEDIUM_RATING;
            case "HARD"   -> LC_HARD_RATING;
            default       -> null;
        };
    }

    private static Set<String> translate(List<String> tags, Map<String, List<String>> vocabulary) {
        Set<String> out = new LinkedHashSet<>();
        if (tags == null) return out;
        for (String tag : tags) {
            String key = clean(tag).replace(' ', '-');
            List<String> mapped = vocabulary.get(key);
            if (mapped == null) mapped = vocabulary.get(clean(tag));
            if (mapped != null) out.addAll(mapped);
        }
        return out;
    }

    /** Lowercase, trimmed, internal whitespace collapsed. */
    private static String clean(String tag) {
        if (tag == null) return "";
        return tag.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }
}
