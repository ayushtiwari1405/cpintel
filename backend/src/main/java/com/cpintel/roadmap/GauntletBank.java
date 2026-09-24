package com.cpintel.roadmap;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The placement gauntlet's questions: six areas, four tiers each, two questions per tier.
 *
 * <h2>Why a gauntlet at all</h2>
 *
 * <p>The roadmap reads mastery from solve history, so somebody new — or somebody whose history
 * lives on a handle they never linked — lands on "Input, Output & Complexity" whatever they can
 * actually do. That is the wrong first screen for a 1900. The gauntlet is ten minutes that
 * answer "where do I stand" directly, and the answer is mapped onto the tree.
 *
 * <h2>How it is scored</h2>
 *
 * <p>Each area climbs its tiers in order, and a tier counts only when <em>both</em> its
 * questions are right. One question per tier would place a quarter of pure guessers a tier too
 * high; two brings that to one in sixteen. The first tier missed ends the climb for that area.
 *
 * <p>Tier ratings are where the roadmap's node bands turn over — see {@link #TIER_RATING}.
 * Passing a tier completes every node in the area's tracks whose band ends at or below it.
 *
 * <h2>Writing questions</h2>
 *
 * <p>The correct option is always written first, so the source is easy to check; the service
 * shuffles options per question before they are served, and scores by the shuffled index, so
 * no client ever sees which one is right until it has answered. Questions test recognising the
 * technique a problem needs, which is what a rating actually measures — not trivia about it.
 */
public final class GauntletBank {

    private GauntletBank() {}

    /** The rating a tier stands for. Index 0 is tier 1. */
    public static final int[] TIER_RATING = { 1200, 1600, 2000, 2400 };

    /** Where somebody who passes no tier in an area is placed: the very beginning. */
    public static final int FLOOR_RATING = 800;

    /**
     * One area of the gauntlet, and the roadmap tracks its result is applied to.
     *
     * @param tracks {@link RoadmapTaxonomy#TRACKS} names this area places
     */
    public record Section(String id, String title, String blurb, List<String> tracks) {}

    /**
     * One question.
     *
     * @param options the correct answer first; shuffled before serving
     * @param code    an optional snippet shown under the prompt, monospaced
     */
    public record Question(String id, String section, int tier, String prompt, String code,
                           List<String> options, String explanation) {}

    public static final List<Section> SECTIONS = List.of(
        new Section("foundations", "Foundations",
            "Complexity, sorting, binary search, greedy, two pointers.",
            List.of("Foundations", "Bit Manipulation", "Techniques")),
        new Section("data-structures", "Data structures",
            "Stacks, heaps, DSU, Fenwick and segment trees.",
            List.of("Data Structures")),
        new Section("graphs", "Graphs & trees",
            "Traversals, shortest paths, LCA, and the harder graph theory.",
            List.of("Graphs", "Trees", "Flows & Matching")),
        new Section("dp", "Dynamic programming",
            "From recurrences to bitmask, digit and optimised DP.",
            List.of("Dynamic Programming", "Game Theory")),
        new Section("math", "Math",
            "Number theory, modular arithmetic, counting, probability.",
            List.of("Math & Number Theory", "Combinatorics & Probability", "Geometry")),
        new Section("strings", "Strings",
            "Hashing, prefix function, tries and suffix structures.",
            List.of("Strings")));

    private static final List<Question> DECLARED = new ArrayList<>();

    private static void q(String id, String section, int tier, String prompt, String code,
                          String explanation, String... options) {
        DECLARED.add(new Question(id, section, tier, prompt, code, List.of(options), explanation));
    }

    static {
        // ══ Foundations ═════════════════════════════════════════════════════

        q("f1a", "foundations", 1,
            "n ≤ 2·10⁵ and the limit is 1 second. Which complexity is safe?", null,
            "Around 10⁸ simple operations fit in a second. n log n is ~3.6·10⁶; n² is 4·10¹⁰.",
            "O(n log n)", "O(n²)", "O(n² / 2)", "O(n³ / 64)");
        q("f1b", "foundations", 1,
            "What does this print?",
            "int a = 100000, b = 100000;\nlong long c = a * b;\nprintf(\"%lld\", c);",
            "a * b is computed in int and overflows before it is widened. Write 1LL * a * b.",
            "Garbage — the multiplication overflows int before the assignment",
            "10000000000", "0", "It does not compile");

        q("f2a", "foundations", 2,
            "All a[i] are positive. Count the subarrays whose sum is exactly S, n = 2·10⁵.", null,
            "Positive values make the window sum monotone, so two pointers move only forward.",
            "Two pointers / sliding window, O(n)",
            "Sort the array, then binary search, O(n log n)",
            "DP over every sum up to S, O(n·S)",
            "Check every subarray, O(n²)");
        q("f2b", "foundations", 2,
            "Split an array into k contiguous parts so the largest part-sum is as small as "
                + "possible. Which technique fits?", null,
            "\"Can every part stay ≤ X?\" is greedy to check and monotone in X, so binary search X.",
            "Binary search on the answer, with a greedy feasibility check",
            "Sort the array and deal elements round-robin",
            "Fill each part up to total / k",
            "Prefix sums alone");

        q("f3a", "foundations", 3,
            "One machine, jobs with length tᵢ and weight wᵢ. Minimise Σ wᵢ·(finish time of i). "
                + "Which order?", null,
            "Swapping two adjacent jobs changes the cost by tⱼwᵢ − tᵢwⱼ; the exchange argument "
                + "gives Smith's rule.",
            "By tᵢ / wᵢ ascending", "By tᵢ ascending", "By wᵢ descending",
            "By tᵢ · wᵢ ascending");
        q("f3b", "foundations", 3,
            "f is strictly increasing, then strictly decreasing, over the integers [0, 10⁹]. "
                + "Find its maximum with few evaluations.", null,
            "Unimodal: ternary search, or binary search on the sign of f(x+1) − f(x).",
            "Ternary search (or binary search on f(x+1) − f(x))",
            "Binary search for f(x) = 0",
            "Evaluate every x",
            "Sort the values of f");

        q("f4a", "foundations", 4,
            "When is parallel binary search the right tool?", null,
            "It runs every query's binary search in lock-step, replaying the updates once per round.",
            "Many queries each need their own binary search over the same sequence of updates",
            "Binary searching two sorted arrays at the same time",
            "Searching a matrix sorted by rows and columns",
            "Any binary search on a multi-core machine");
        q("f4b", "foundations", 4,
            "Ternary search on integers, but f has flat stretches (f(m₁) = f(m₂) away from the "
                + "peak). What goes wrong?", null,
            "Equal probes on a plateau say nothing about which side the peak is on.",
            "Equal values at the two probes give no information, so it can discard the peak",
            "It becomes O(n)",
            "It needs floating point to work",
            "Nothing — ternary search handles plateaus");

        // ══ Data structures ═════════════════════════════════════════════════

        q("d1a", "data-structures", 1,
            "Which structure answers \"have I seen this value before?\" in O(1) on average?", null,
            "Hashing gives expected O(1) insert and lookup.",
            "A hash set", "A sorted array", "A linked list", "A stack");
        q("d1b", "data-structures", 1,
            "Check whether a sequence of ()[]{} is balanced. The natural structure is:", null,
            "Each closer must match the most recent unmatched opener — last in, first out.",
            "A stack", "A queue", "A heap", "A set");

        q("d2a", "data-structures", 2,
            "Repeatedly remove the two smallest numbers and insert their sum, n = 2·10⁵.", null,
            "A min-heap gives both removals and the insertion in O(log n).",
            "A min-heap (priority queue)", "A sorted array with insertion", "A stack",
            "A hash map");
        q("d2b", "data-structures", 2,
            "q operations: merge the groups containing x and y, or ask whether x and y are in "
                + "the same group.", null,
            "Disjoint set union answers both in near-constant amortised time.",
            "Disjoint set union with path compression", "BFS for every query",
            "A segment tree", "Sort the pairs");

        q("d3a", "data-structures", 3,
            "Point updates and range-sum queries, n and q up to 2·10⁵.", null,
            "Fenwick (or segment) tree: O(log n) each. A sparse table cannot be updated.",
            "A Fenwick tree", "Rebuild a prefix-sum array after each update",
            "A sparse table", "A hash map");
        q("d3b", "data-structures", 3,
            "For every element, the nearest element to its left that is smaller — O(n) total.",
            null,
            "Keep a stack of increasing values; pop everything not smaller than the current one.",
            "A monotonic stack", "A min-heap", "Binary search over prefix minima",
            "A sliding-window deque of fixed size");

        q("d4a", "data-structures", 4,
            "Range add and range sum, 2·10⁵ of each.", null,
            "Lazy propagation defers each range update to the nodes a later query touches.",
            "A segment tree with lazy propagation", "A sparse table",
            "A Fenwick tree with point updates only", "Update every element, query with prefix sums");
        q("d4b", "data-structures", 4,
            "Offline: the number of distinct values in a[l..r], for 2·10⁵ queries.", null,
            "Sweep r; keep a 1 only at each value's latest position; a range sum is the answer.",
            "Sort queries by r and use a Fenwick tree over each value's last occurrence",
            "A sparse table of distinct counts",
            "Prefix sums of distinct counts",
            "A segment tree that adds its children's distinct counts");

        // ══ Graphs & trees ══════════════════════════════════════════════════

        q("g1a", "graphs", 1,
            "Shortest paths from one source in an unweighted graph.", null,
            "BFS reaches vertices in order of distance.",
            "Breadth-first search", "Depth-first search", "Dijkstra with a max-heap",
            "Floyd–Warshall");
        q("g1b", "graphs", 1,
            "A tree with n vertices has how many edges?", null,
            "Connected and acyclic: exactly n − 1.",
            "n − 1", "n", "n + 1", "2n");

        q("g2a", "graphs", 2,
            "Tasks must happen after some others. Find an order, or say none exists.", null,
            "Topological sort; it fails exactly when the dependencies contain a cycle.",
            "Topological sort — impossible exactly when there is a cycle",
            "BFS from task 1", "Dijkstra's algorithm", "A minimum spanning tree");
        q("g2b", "graphs", 2,
            "Dijkstra's algorithm can give wrong answers when the graph:", null,
            "It finalises a vertex when popped, which is only safe if edges never shorten paths.",
            "Has negative edge weights", "Is undirected", "Has cycles", "Is disconnected");

        q("g3a", "graphs", 3,
            "2·10⁵ queries of \"lowest common ancestor of u and v\" on a fixed tree.", null,
            "Binary lifting: O(n log n) to build, O(log n) per query.",
            "Binary lifting", "BFS for every query", "Disjoint set union online",
            "Topological sort");
        q("g3b", "graphs", 3,
            "Edge weights are only 0 or 1. Single-source shortest paths in O(V + E)?", null,
            "0-1 BFS: push 0-edges to the front of a deque and 1-edges to the back.",
            "0-1 BFS with a deque", "Plain BFS", "Bellman–Ford", "Floyd–Warshall");

        q("g4a", "graphs", 4,
            "An AND of clauses, each an OR of two literals. Is it satisfiable?", null,
            "2-SAT: build the implication graph; unsatisfiable iff x and ¬x share an SCC.",
            "Implication graph — unsatisfiable exactly when some x and ¬x share an SCC",
            "NP-hard, so try every assignment",
            "Set every variable true, then fix conflicts greedily",
            "Check whether the clause graph is bipartite");
        q("g4b", "graphs", 4,
            "A tree with edge weights that change, and queries \"max edge on the path u–v\", "
                + "2·10⁵ of each.", null,
            "Heavy-light decomposition turns a path into O(log n) segment-tree ranges.",
            "Heavy-light decomposition with a segment tree",
            "Binary lifting tables", "BFS for every query", "Disjoint set union");

        // ══ Dynamic programming ═════════════════════════════════════════════

        q("p1a", "dp", 1,
            "Ways to climb n stairs taking 1 or 2 steps at a time. ways(n) =", null,
            "The last step was either 1 or 2.",
            "ways(n−1) + ways(n−2)", "2 · ways(n−1)", "ways(n−1) · ways(n−2)", "n²");
        q("p1b", "dp", 1,
            "Why does memoisation make a recursive solution fast?", null,
            "The recursion revisits the same states; storing them makes each cost O(1) after the first.",
            "Each distinct state is computed once and then reused",
            "It removes the recursion depth limit", "It sorts the input first",
            "It uses less memory");

        q("p2a", "dp", 2,
            "0/1 knapsack with n = 100 items and capacity W = 10⁵. The standard DP runs in:",
            null, "One pass over capacities per item: O(n·W) = 10⁷.",
            "O(n · W)", "O(2ⁿ)", "O(n log n)", "O(W log W)");
        q("p2b", "dp", 2,
            "Longest increasing subsequence, n = 2·10⁵.", null,
            "Keep the smallest possible tail for each length; binary search where each value goes.",
            "O(n log n), keeping the smallest tail for each length",
            "O(n²) DP over pairs", "Sort and count distinct values",
            "Greedily take every increase");

        q("p3a", "dp", 3,
            "Shortest tour through all of n = 16 cities, exactly.", null,
            "DP over (visited set, current city): O(2ⁿ · n²) ≈ 6.7·10⁷.",
            "Bitmask DP over the set of visited cities", "Nearest-neighbour greedy",
            "Dijkstra from every city", "Try all 16! orders");
        q("p3b", "dp", 3,
            "Count the integers in [1, 10¹⁸] whose digit sum is divisible by 7.", null,
            "Digit DP over (position, digit sum mod 7, still tight to the bound).",
            "Digit DP over (position, sum mod 7, tight)", "Iterate over every integer",
            "A sieve up to 10¹⁸", "Binary search on the count");

        q("p4a", "dp", 4,
            "dp[i] = min over j < i of (dp[j] + b[j] · a[i]), with b monotone. How to beat O(n²)?",
            null, "Each j is a line b[j]·x + dp[j]; query the lower envelope at x = a[i].",
            "Convex hull trick (or a Li Chao tree)", "Knuth's optimisation",
            "Bitmask DP", "A sparse table over dp");
        q("p4b", "dp", 4,
            "For every mask over 20 bits, the sum of f over all its submasks, in one second.",
            null, "Sum over subsets: fold in one bit at a time, O(n·2ⁿ) ≈ 2·10⁷. 3ⁿ is 3.5·10⁹.",
            "SOS DP: for each bit, add f[mask without that bit] — O(n·2ⁿ)",
            "Enumerate the submasks of every mask — O(3ⁿ)",
            "FFT over the masks", "Inclusion–exclusion for each mask — O(4ⁿ)");

        // ══ Math ════════════════════════════════════════════════════════════

        q("m1a", "math", 1, "gcd(84, 36) =", null,
            "84 = 2²·3·7 and 36 = 2²·3², so the gcd is 2²·3.",
            "12", "6", "18", "4");
        q("m1b", "math", 1,
            "Testing whether n ≤ 10¹² is prime by trial division needs divisors up to:", null,
            "Any factorisation has a factor at most √n.",
            "√n, which is 10⁶", "n / 2", "log n", "n");

        q("m2a", "math", 2, "Compute aᵇ mod m for b up to 10¹⁸.", null,
            "Square-and-multiply needs O(log b) multiplications.",
            "Binary exponentiation", "Multiply by a, b times", "Reduce b modulo m first",
            "Use doubles and round");
        q("m2b", "math", 2,
            "Many queries of C(n, k) mod 10⁹+7 with n ≤ 10⁶.", null,
            "Precompute n! and its modular inverse; each query is two multiplications.",
            "Precompute factorials and inverse factorials",
            "Pascal's triangle up to 10⁶ × 10⁶",
            "n! / (k!(n−k)!) with integer division, mod p",
            "Compute with doubles");

        q("m3a", "math", 3,
            "p is prime and does not divide a. The inverse of a modulo p is:", null,
            "Fermat: aᵖ⁻¹ ≡ 1, so a · aᵖ⁻² ≡ 1 (mod p).",
            "aᵖ⁻² mod p", "aᵖ⁻¹ mod p", "p − a", "a mod (p − 1)");
        q("m3b", "math", 3,
            "Expected number of rolls of a fair die until the first 6.", null,
            "A geometric distribution with success 1/6 has mean 6.",
            "6", "3.5", "5", "36");

        q("m4a", "math", 4,
            "Multiply two polynomials of degree 10⁵, coefficients mod 998244353.", null,
            "998244353 = 119·2²³ + 1 supports the number-theoretic transform: O(n log n).",
            "NTT (number-theoretic transform)", "Schoolbook multiplication",
            "Matrix exponentiation", "The Chinese remainder theorem");
        q("m4b", "math", 4,
            "Colourings of an n-bead necklace with k colours, rotations counted as the same.",
            null, "Burnside: average the fixed colourings over the n rotations, k^gcd(i, n) each.",
            "Burnside's lemma: (1/n) · Σ k^gcd(i, n)", "kⁿ / n", "kⁿ",
            "C(n + k − 1, k − 1)");

        // ══ Strings ═════════════════════════════════════════════════════════

        q("s1a", "strings", 1, "Check whether a string is a palindrome in O(n).", null,
            "Compare mirrored positions from both ends.",
            "Compare s[i] with s[n−1−i] for every i", "Sort it and compare",
            "Hash every substring", "Count its vowels");
        q("s1b", "strings", 1,
            "Why is this slow for 10⁵ iterations in C++?",
            "string s;\nfor (int i = 0; i < 100000; i++)\n    s = s + 'a';",
            "s + 'a' builds a new string and copies it: quadratic. s += 'a' appends in place.",
            "Each s + 'a' builds and copies a whole new string, so the loop is quadratic",
            "Strings cannot be modified in C++", "A char is 4 bytes",
            "It is not slow");

        q("s2a", "strings", 2,
            "Compare any two substrings for equality in O(1) after O(n) preprocessing.", null,
            "Prefix polynomial hashes give any substring's hash in O(1).",
            "Prefix polynomial hashes", "Sort all substrings", "KMP for each query",
            "A trie of all substrings");
        q("s2b", "strings", 2,
            "Store 10⁵ words and answer \"how many start with prefix p?\"", null,
            "Walk p down a trie; the count stored at that node is the answer.",
            "A trie with a count at each node", "A hash set of the words", "A stack",
            "A min-heap");

        q("s3a", "strings", 3,
            "Find every occurrence of pattern P in text T in O(|P| + |T|).", null,
            "Prefix function (KMP) or Z-function on P + '#' + T.",
            "KMP / the prefix function", "Check each starting position", "Sort the suffixes",
            "Binary search");
        q("s3b", "strings", 3, "The prefix function π[i] of a string s is:", null,
            "The length of the longest proper prefix of s[0..i] that is also its suffix.",
            "The longest proper prefix of s[0..i] that is also a suffix of it",
            "The number of distinct prefixes of s[0..i]",
            "The longest palindrome ending at i",
            "The position of the previous occurrence of s[i]");

        q("s4a", "strings", 4, "Count the distinct substrings of a string of length 10⁵.", null,
            "Each suffix adds its length minus its LCP with the previous suffix in sorted order.",
            "Suffix array with LCP: n(n+1)/2 − Σ LCP",
            "Insert every substring into a hash set", "One Z-function pass",
            "Manacher's algorithm");
        q("s4b", "strings", 4,
            "Find all occurrences of 10⁴ patterns (total length 10⁶) in one text.", null,
            "One automaton over all patterns scans the text once.",
            "Aho–Corasick", "KMP once per pattern", "Manacher's algorithm",
            "A single Z-function pass");
    }

    public static final List<Question> QUESTIONS = List.copyOf(DECLARED);

    public static final Map<String, Question> BY_ID;

    static {
        Map<String, Question> byId = new LinkedHashMap<>();
        for (Question question : QUESTIONS) {
            if (byId.put(question.id(), question) != null) {
                throw new IllegalStateException("Duplicate gauntlet question " + question.id());
            }
            if (question.tier() < 1 || question.tier() > TIER_RATING.length) {
                throw new IllegalStateException("Question " + question.id() + " has no tier");
            }
        }
        BY_ID = Collections.unmodifiableMap(byId);
    }
}
