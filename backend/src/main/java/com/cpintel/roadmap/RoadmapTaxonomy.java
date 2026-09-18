package com.cpintel.roadmap;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The skill tree: every sub-skill CPIntel tracks, what it depends on, and how a Codeforces
 * problem is recognised as belonging to it.
 *
 * <p>This file is the single source of truth for two things that used to disagree. Mastery was
 * kept against fourteen coarse topic names produced by {@code TopicTagMapper}, while the tree
 * held thirty-five nodes keyed on their display names — and the unlock check joined the two on
 * {@code node.topic}, which is a display name and therefore matched almost nothing. Every node
 * below the handful whose display name happened to equal a canonical topic read a mastery of
 * zero and stayed {@code LOCKED} forever.
 *
 * <p>The fix is to stop having two taxonomies. A node is now the unit of mastery: it carries
 * its own tag and rating rules, its own solved/attempted counts, and its own score.
 * {@link #rollupTopics()} derives the coarse topics from the nodes for the radar chart, so the
 * broad view is a projection of the detailed one rather than a parallel set of numbers.
 *
 * <h2>How a problem is attributed to a node</h2>
 *
 * A problem belongs to a node when its rating falls inside the node's band <em>and</em> its
 * tags satisfy the node's tag rule. Two rules exist because Codeforces tags are broad:
 *
 * <ul>
 *   <li>{@code anyTags} — at least one must be present. Enough for a node that owns its tag
 *       outright, like {@code 2-sat} or {@code fft}.
 *   <li>{@code allTags} — every one must be present. This is what separates the nodes that
 *       share a tag: {@code dp} alone is DP basics, {@code dp}+{@code bitmasks} is bitmask DP,
 *       {@code dp}+{@code trees} is DP on trees. Without it a single {@code dp} tag would
 *       credit every DP node at once and the tree would carry no information.
 * </ul>
 *
 * <p>A problem may legitimately belong to several nodes — a 1900 DP-over-bitmasks problem is
 * evidence for both bitmask DP and subset enumeration — so attribution is deliberately a set,
 * not a single winner.
 *
 * <p>Only tags Codeforces actually publishes are used here. A node whose real technique has no
 * Codeforces tag (monotonic stacks, sqrt decomposition) is matched on the closest tag it does
 * carry plus a rating band; that is a proxy, and the band is doing most of the work.
 */
public final class RoadmapTaxonomy {

    private RoadmapTaxonomy() {}

    /** Ordered top-level groupings. The UI renders one column or section per track. */
    public static final List<String> TRACKS = List.of(
        "Foundations",
        "Strings",
        "Bit Manipulation",
        "Data Structures",
        "Graphs",
        "Trees",
        "Math & Number Theory",
        "Combinatorics & Probability",
        "Geometry",
        "Dynamic Programming",
        "Flows & Matching",
        "Game Theory",
        "Techniques"
    );

    /**
     * One sub-skill.
     *
     * @param id          stable key; used as the mastery row's identity, so never renamed casually
     * @param displayName what the user reads
     * @param track       top-level grouping, one of {@link #TRACKS}
     * @param rollupTopic coarse topic this contributes to on the radar chart
     * @param prereqIds   nodes that must be reached before this one unlocks
     * @param anyTags     Codeforces tags, at least one of which must be present
     * @param allTags     Codeforces tags, all of which must be present; may be empty
     * @param minRating   inclusive lower bound of the node's problem band
     * @param maxRating   inclusive upper bound of the node's problem band
     * @param orderIndex  position in the whole tree, assigned from declaration order
     * @param blurb       one line explaining what the skill is, shown in the detail panel
     */
    public record NodeDef(
        String id,
        String displayName,
        String track,
        String rollupTopic,
        List<String> prereqIds,
        List<String> anyTags,
        List<String> allTags,
        int minRating,
        int maxRating,
        int orderIndex,
        String blurb
    ) {
        /** True when a problem with this rating and these tags is evidence for this node. */
        public boolean matches(Integer rating, Set<String> lowercaseTags) {
            if (rating == null || rating < minRating || rating > maxRating) return false;
            if (lowercaseTags == null || lowercaseTags.isEmpty()) return false;
            for (String required : allTags) {
                if (!lowercaseTags.contains(required)) return false;
            }
            if (anyTags.isEmpty()) return true;
            for (String candidate : anyTags) {
                if (lowercaseTags.contains(candidate)) return true;
            }
            return false;
        }
    }

    // ── declaration helpers ────────────────────────────────────────────────
    //
    // orderIndex is assigned from position rather than typed by hand. A hundred and forty
    // hand-numbered indices drift the first time a node is inserted in the middle, and the
    // drift is silent because nothing validates them.

    private static final List<NodeDef> DECLARED = new ArrayList<>();

    private static void node(String id, String displayName, String track, String rollupTopic,
                             List<String> prereqIds, List<String> anyTags, List<String> allTags,
                             int minRating, int maxRating, String blurb) {
        DECLARED.add(new NodeDef(id, displayName, track, rollupTopic,
            List.copyOf(prereqIds), List.copyOf(anyTags), List.copyOf(allTags),
            minRating, maxRating, DECLARED.size() + 1, blurb));
    }

    private static List<String> after(String... ids) { return List.of(ids); }
    private static List<String> tags(String... t)    { return List.of(t); }
    private static List<String> none()               { return List.of(); }

    static {
        // ══ Foundations ═════════════════════════════════════════════════════


        node("io-basics", "Input, Output & Complexity", "Foundations", "Foundations",
            none(), tags("implementation"), none(), 800, 1000,
            "Reading input fast, printing correctly, and estimating whether a loop will finish in time.");

        node("arrays-basics", "Array Manipulation", "Foundations", "Arrays",
            after("io-basics"), tags("implementation"), none(), 800, 1100,
            "Indexing, in-place updates and the off-by-one errors that cost the most contest time.");

        node("simulation", "Simulation & Ad-hoc", "Foundations", "Foundations",
            after("io-basics"), tags("implementation"), none(), 800, 1200,
            "Problems that simply ask you to do exactly what the statement says, without error.");

        node("brute-force", "Brute Force & Enumeration", "Foundations", "Foundations",
            after("io-basics"), tags("brute force"), none(), 800, 1200,
            "Trying every candidate when the constraints quietly tell you that you can.");

        node("sorting-basics", "Sorting & Comparators", "Foundations", "Sorting & Searching",
            after("arrays-basics"), tags("sortings"), none(), 900, 1200,
            "Sorting as a preprocessing step, and writing a comparator that is a strict weak ordering.");

        node("custom-sorting", "Custom Sort Orders", "Foundations", "Sorting & Searching",
            after("sorting-basics"), tags("sortings"), tags("sortings", "greedy"), 1200, 1600,
            "Choosing the order that makes a greedy argument work — the hard half of most greedy problems.");

        node("prefix-sums", "Prefix Sums", "Foundations", "Arrays",
            after("arrays-basics"), tags("implementation", "data structures"), none(), 1000, 1300,
            "Answering range-sum questions in constant time after one linear pass.");

        node("difference-arrays", "Difference Arrays", "Foundations", "Arrays",
            after("prefix-sums"), tags("implementation", "data structures"), none(), 1300, 1600,
            "Applying many range updates offline, then recovering the array with one prefix pass.");

        node("prefix-2d", "2D Prefix Sums", "Foundations", "Arrays",
            after("prefix-sums"), tags("implementation", "dp"), none(), 1400, 1700,
            "The same trick on a grid: any submatrix sum from four lookups.");

        node("two-pointers", "Two Pointers", "Foundations", "Two Pointers",
            after("sorting-basics"), tags("two pointers"), none(), 1100, 1500,
            "Walking two indices in one direction to turn a quadratic scan into a linear one.");

        node("sliding-window", "Sliding Window", "Foundations", "Two Pointers",
            after("two-pointers"), tags("two pointers"), none(), 1300, 1700,
            "Maintaining a window that satisfies a predicate while both ends only move forward.");

        node("binary-search-basics", "Binary Search on Arrays", "Foundations", "Sorting & Searching",
            after("sorting-basics"), tags("binary search"), none(), 1100, 1400,
            "Finding a boundary in a sorted array without writing an off-by-one.");

        node("binary-search-answer", "Binary Search on the Answer", "Foundations", "Sorting & Searching",
            after("binary-search-basics"), tags("binary search"), none(), 1500, 1900,
            "Guessing the answer and checking feasibility, when the check is easier than the search.");

        node("ternary-search", "Ternary Search", "Foundations", "Sorting & Searching",
            after("binary-search-answer"), tags("ternary search"), none(), 1700, 2100,
            "Locating the extremum of a unimodal function, in integers or in reals.");

        node("divide-conquer", "Divide & Conquer", "Foundations", "Sorting & Searching",
            after("sorting-basics"), tags("divide and conquer"), none(), 1600, 2000,
            "Splitting a problem in half, solving both, and paying only for the merge.");

        node("greedy-basics", "Greedy Basics", "Foundations", "Greedy",
            after("sorting-basics"), tags("greedy"), none(), 1000, 1400,
            "Taking the locally best option, and knowing the cases where that is actually optimal.");

        node("exchange-argument", "Exchange Arguments", "Foundations", "Greedy",
            after("greedy-basics"), tags("greedy"), tags("greedy", "sortings"), 1500, 1900,
            "Proving a greedy order correct by showing any swap makes the answer no better.");

        node("constructive", "Constructive Algorithms", "Foundations", "Greedy",
            after("greedy-basics"), tags("constructive algorithms"), none(), 1200, 1700,
            "Building a valid answer directly instead of searching for one.");

        node("constructive-advanced", "Advanced Constructives", "Foundations", "Greedy",
            after("constructive"), tags("constructive algorithms"), none(), 1900, 2400,
            "Constructions that need an invariant or a parity argument before the pattern appears.");

        // ══ Strings ═════════════════════════════════════════════════════════


        node("strings-basics", "String Basics", "Strings", "Strings",
            after("io-basics"), tags("strings"), none(), 800, 1200,
            "Characters, substrings, and the cost of building a string one concatenation at a time.");

        node("string-implementation", "String Manipulation", "Strings", "Strings",
            after("strings-basics"), tags("strings"), tags("strings", "implementation"), 1000, 1400,
            "Careful case work over a string, where the difficulty is entirely in the details.");

        node("palindromes", "Palindromes", "Strings", "Strings",
            after("strings-basics"), tags("strings"), none(), 1200, 1700,
            "Recognising, counting and building palindromes with two-pointer and DP arguments.");

        node("string-hashing", "String Hashing", "Strings", "Strings",
            after("strings-basics"), tags("hashing"), none(), 1500, 1900,
            "Polynomial hashing for O(1) substring comparison, and the collisions you must respect.");

        node("kmp", "KMP & the Prefix Function", "Strings", "Strings",
            after("string-hashing"), tags("string suffix structures", "strings"), none(), 1700, 2100,
            "The failure function: borders of every prefix, computed in linear time.");

        node("z-function", "Z-Function", "Strings", "Strings",
            after("kmp"), tags("string suffix structures", "strings"), none(), 1700, 2100,
            "Longest common prefix with the whole string at every position.");

        node("tries", "Tries", "Strings", "Tries",
            after("strings-basics"), tags("data structures"), none(), 1500, 1900,
            "A prefix tree over a dictionary, for prefix queries and shared-prefix counting.");

        node("aho-corasick", "Aho-Corasick", "Strings", "Tries",
            after("tries", "kmp"), tags("string suffix structures"), none(), 2200, 2700,
            "A trie with failure links: match every pattern in a dictionary during one pass.");

        node("manacher", "Manacher's Algorithm", "Strings", "Strings",
            after("palindromes", "z-function"), tags("string suffix structures", "strings"), none(), 2100, 2500,
            "Every maximal palindromic substring in linear time.");

        node("suffix-array", "Suffix Arrays", "Strings", "Strings",
            after("z-function"), tags("string suffix structures"), none(), 2400, 2900,
            "All suffixes in sorted order, plus the LCP array that makes it useful.");

        node("suffix-automaton", "Suffix Automaton", "Strings", "Strings",
            after("suffix-array"), tags("string suffix structures"), none(), 2600, 3000,
            "A minimal automaton recognising every substring — distinct substring counting and more.");

        node("expression-parsing", "Expression Parsing", "Strings", "Strings",
            after("string-implementation"), tags("expression parsing"), none(), 1600, 2100,
            "Turning an infix expression into a value or a tree, with correct precedence.");

        // ══ Bit Manipulation ════════════════════════════════════════════════


        node("bitwise-basics", "Bitwise Operations", "Bit Manipulation", "Bit Manipulation",
            after("io-basics"), tags("bitmasks"), none(), 1000, 1400,
            "AND, OR, XOR, shifts, popcount, and reading a number as a set of bits.");

        node("bitmask-enumeration", "Subset Enumeration", "Bit Manipulation", "Bit Manipulation",
            after("bitwise-basics"), tags("bitmasks"), tags("bitmasks", "brute force"), 1400, 1800,
            "Iterating every subset — and every subset of a subset — with an integer mask.");

        node("xor-tricks", "XOR Properties & Tricks", "Bit Manipulation", "Bit Manipulation",
            after("bitwise-basics"), tags("bitmasks"), tags("bitmasks", "math"), 1500, 1900,
            "XOR as addition without carry: prefix xors, pairing, and cancelling duplicates.");

        node("bitwise-trie", "Binary Trie for XOR Queries", "Bit Manipulation", "Bit Manipulation",
            after("xor-tricks", "tries"), tags("data structures", "bitmasks"), none(), 1700, 2200,
            "Storing numbers bit by bit so maximum-XOR queries take one root-to-leaf walk.");

        // ══ Data Structures ═════════════════════════════════════════════════


        node("stacks", "Stacks", "Data Structures", "Data Structures",
            after("arrays-basics"), tags("data structures"), none(), 1100, 1500,
            "Last-in-first-out processing: bracket matching, undo, and iterative traversal.");

        node("queues-deques", "Queues & Deques", "Data Structures", "Data Structures",
            after("stacks"), tags("data structures"), none(), 1200, 1600,
            "First-in-first-out and double-ended processing.");

        node("monotonic-stack", "Monotonic Stack", "Data Structures", "Data Structures",
            after("stacks"), tags("data structures"), none(), 1500, 1900,
            "Next-greater-element in linear time, and the largest-rectangle family it unlocks.");

        node("monotonic-deque", "Monotonic Deque", "Data Structures", "Data Structures",
            after("monotonic-stack", "sliding-window"), tags("data structures", "two pointers"), none(), 1600, 2000,
            "Sliding-window minimum and maximum with amortised constant cost per element.");

        node("heaps", "Heaps & Priority Queues", "Data Structures", "Data Structures",
            after("sorting-basics"), tags("data structures", "greedy"), none(), 1400, 1800,
            "Always taking the current best, when what is best keeps changing.");

        node("ordered-sets", "Ordered Sets & Multisets", "Data Structures", "Data Structures",
            after("sorting-basics"), tags("data structures"), none(), 1400, 1800,
            "Insert, erase, and find-the-neighbour in logarithmic time over a changing set.");

        node("hashmaps", "Hash Maps & Frequency Counting", "Data Structures", "Data Structures",
            after("arrays-basics"), tags("data structures"), tags("data structures", "implementation"), 1100, 1500,
            "Counting occurrences, and the anti-hash tests that punish a predictable hash.");

        node("dsu-basics", "Disjoint Set Union", "Data Structures", "Data Structures",
            after("arrays-basics"), tags("dsu"), none(), 1400, 1800,
            "Union by size with path compression: connectivity under edge additions.");

        node("dsu-rollback", "DSU with Rollback", "Data Structures", "Data Structures",
            after("dsu-basics"), tags("dsu"), none(), 2200, 2600,
            "Undoing unions to run DSU inside a divide-and-conquer over time.");


        node("sparse-table", "Sparse Table & Static RMQ", "Data Structures", "Data Structures",
            after("prefix-sums"), tags("data structures"), none(), 1700, 2100,
            "O(1) range minimum on an array that never changes.");

        node("fenwick", "Fenwick Tree (BIT)", "Data Structures", "Data Structures",
            after("prefix-sums"), tags("data structures"), none(), 1600, 2000,
            "Prefix sums under point updates, in twenty lines.");

        node("fenwick-2d", "2D Fenwick Tree", "Data Structures", "Data Structures",
            after("fenwick", "prefix-2d"), tags("data structures"), none(), 2100, 2500,
            "The same structure nested, for rectangle sums under point updates.");

        node("segment-tree", "Segment Tree", "Data Structures", "Segment Trees",
            after("fenwick"), tags("data structures"), none(), 1700, 2100,
            "Any associative range query under point updates.");

        node("segment-tree-lazy", "Lazy Propagation", "Data Structures", "Segment Trees",
            after("segment-tree"), tags("data structures"), none(), 2000, 2400,
            "Range updates deferred down the tree and applied only when a node is visited.");

        node("segment-tree-merge", "Merge Sort Tree", "Data Structures", "Segment Trees",
            after("segment-tree"), tags("data structures"), none(), 2200, 2600,
            "A sorted list at every node, for counting values in a range.");

        node("segment-tree-persistent", "Persistent Segment Tree", "Data Structures", "Segment Trees",
            after("segment-tree-lazy"), tags("data structures"), none(), 2400, 2900,
            "Keeping every historical version by sharing all the nodes an update does not touch.");

        node("segment-tree-beats", "Segment Tree Beats", "Data Structures", "Segment Trees",
            after("segment-tree-lazy"), tags("data structures"), none(), 2700, 3100,
            "Range chmin and chmax, with an amortised argument that they are still fast.");

        node("treap", "Balanced BST / Treap", "Data Structures", "Data Structures",
            after("segment-tree-lazy"), tags("data structures"), none(), 2400, 2900,
            "Split and merge on an implicit key, for insert and erase in the middle of a sequence.");

        node("sqrt-decomposition", "Sqrt Decomposition", "Data Structures", "Data Structures",
            after("prefix-sums"), tags("data structures"), none(), 2000, 2500,
            "Blocks of root-n elements: when a segment tree cannot express the operation.");

        node("mos-algorithm", "Mo's Algorithm", "Data Structures", "Data Structures",
            after("sqrt-decomposition"), tags("data structures"), none(), 2200, 2700,
            "Reordering offline queries so the window moves as little as possible.");

        node("offline-queries", "Offline Query Processing", "Data Structures", "Data Structures",
            after("sqrt-decomposition"), tags("data structures", "sortings"), none(), 2100, 2600,
            "Sorting the questions before answering them, when they may be answered out of order.");

        // ══ Graphs ══════════════════════════════════════════════════════════


        node("graph-representation", "Graph Representation", "Graphs", "Graphs",
            after("arrays-basics"), tags("graphs"), none(), 1000, 1300,
            "Adjacency lists, edge lists, and choosing between them by the constraints.");

        node("dfs", "Depth-First Search", "Graphs", "Graphs",
            after("graph-representation"), tags("dfs and similar"), none(), 1200, 1500,
            "Exhaustive traversal, and the recursion depth that will stack-overflow if ignored.");

        node("bfs", "Breadth-First Search", "Graphs", "Graphs",
            after("graph-representation"), tags("graphs", "shortest paths"), none(), 1200, 1500,
            "Shortest paths when every edge costs the same.");

        node("connected-components", "Connected Components", "Graphs", "Graphs",
            after("dfs"), tags("dfs and similar", "graphs"), none(), 1300, 1600,
            "Partitioning a graph into its pieces, and counting or comparing them.");

        node("cycle-detection", "Cycle Detection", "Graphs", "Graphs",
            after("dfs"), tags("dfs and similar", "graphs"), none(), 1500, 1800,
            "Finding a cycle, and recovering it, in both directed and undirected graphs.");

        node("bipartite", "Bipartite Checking & Colouring", "Graphs", "Graphs",
            after("bfs"), tags("dfs and similar", "graphs"), none(), 1500, 1900,
            "Two-colouring a graph, and what an odd cycle rules out.");

        node("topological-sort", "Topological Sort", "Graphs", "Graphs",
            after("dfs"), tags("graphs", "dfs and similar"), none(), 1500, 1900,
            "Ordering a DAG so every edge points forward.");

        node("functional-graphs", "Functional Graphs", "Graphs", "Graphs",
            after("cycle-detection"), tags("graphs", "dfs and similar"), none(), 1900, 2300,
            "Graphs where every node has out-degree one: rho shapes, cycles and the trees hanging off them.");

        node("dijkstra", "Dijkstra's Algorithm", "Graphs", "Graphs",
            after("bfs", "heaps"), tags("shortest paths"), none(), 1600, 2000,
            "Shortest paths with non-negative weights, using a priority queue.");

        node("zero-one-bfs", "0-1 BFS", "Graphs", "Graphs",
            after("dijkstra"), tags("shortest paths", "graphs"), none(), 1900, 2300,
            "A deque instead of a heap when every edge costs zero or one.");

        node("bellman-ford", "Bellman-Ford & Negative Cycles", "Graphs", "Graphs",
            after("dijkstra"), tags("shortest paths", "graphs"), none(), 1800, 2200,
            "Shortest paths that tolerate negative edges, and detecting when none exists.");

        node("floyd-warshall", "Floyd-Warshall", "Graphs", "Graphs",
            after("graph-representation"), tags("shortest paths", "graphs"), none(), 1600, 2000,
            "All-pairs shortest paths in a triple loop, for the small dense case.");

        node("mst-kruskal", "Minimum Spanning Tree: Kruskal", "Graphs", "Graphs",
            after("dsu-basics"), tags("graphs", "dsu"), none(), 1700, 2100,
            "Sorting edges and adding the ones that join two components.");

        node("mst-prim", "Minimum Spanning Tree: Prim", "Graphs", "Graphs",
            after("mst-kruskal", "heaps"), tags("graphs"), none(), 1700, 2100,
            "Growing one tree outward, better than Kruskal when the graph is dense.");

        node("scc", "Strongly Connected Components", "Graphs", "Graphs",
            after("topological-sort"), tags("graphs", "dfs and similar"), none(), 2100, 2500,
            "Tarjan or Kosaraju, and the condensation DAG that makes the rest easy.");

        node("two-sat", "2-SAT", "Graphs", "Graphs",
            after("scc"), tags("2-sat"), none(), 2400, 2800,
            "Boolean constraints as an implication graph, solved by strongly connected components.");

        node("bridges-articulation", "Bridges & Articulation Points", "Graphs", "Graphs",
            after("dfs"), tags("graphs", "dfs and similar"), none(), 2100, 2500,
            "The edges and vertices whose removal disconnects the graph.");

        node("euler-path", "Eulerian Paths & Circuits", "Graphs", "Graphs",
            after("connected-components"), tags("graphs"), none(), 2100, 2500,
            "Walking every edge exactly once, and the degree conditions that permit it.");

        // ══ Trees ═══════════════════════════════════════════════════════════


        node("tree-basics", "Tree Basics & Properties", "Trees", "Trees",
            after("dfs"), tags("trees"), none(), 1200, 1500,
            "n-1 edges, unique paths, and rooting a tree to give every node a parent.");

        node("tree-traversal", "Tree Traversal & Subtree Sizes", "Trees", "Trees",
            after("tree-basics"), tags("trees", "dfs and similar"), none(), 1300, 1700,
            "One DFS that computes depth, parent and subtree size for everything at once.");

        node("dsu-small-to-large", "Small-to-Large Merging", "Data Structures", "Data Structures",
            after("dsu-basics", "tree-traversal"), tags("dsu", "trees"), none(), 2100, 2500,
            "Merging the smaller structure into the larger one for an extra log, never more.");

        node("tree-diameter", "Tree Diameter & Eccentricity", "Trees", "Trees",
            after("tree-traversal"), tags("trees", "dfs and similar"), none(), 1500, 1900,
            "The longest path in a tree, from two traversals or one DP.");

        node("lca-binary-lifting", "LCA via Binary Lifting", "Trees", "Binary Lifting",
            after("tree-traversal"), tags("trees"), none(), 1800, 2200,
            "Jump pointers at powers of two, for lowest common ancestor in log time.");

        node("binary-lifting-general", "Binary Lifting (general)", "Trees", "Binary Lifting",
            after("lca-binary-lifting"), tags("trees", "dp"), none(), 1900, 2300,
            "The same doubling idea applied to any function iterated along a path.");

        node("euler-tour", "Euler Tour on Trees", "Trees", "Trees",
            after("tree-traversal"), tags("trees", "data structures"), none(), 1900, 2300,
            "Flattening a tree into an array so a subtree becomes a contiguous range.");

        node("tree-flattening", "Subtree Queries via Flattening", "Trees", "Trees",
            after("euler-tour", "fenwick"), tags("trees", "data structures"), none(), 2000, 2400,
            "Running an array structure over the Euler tour to answer subtree queries.");

        node("hld", "Heavy-Light Decomposition", "Trees", "Trees",
            after("tree-flattening", "segment-tree-lazy"), tags("trees", "data structures"), none(), 2400, 2900,
            "Cutting a tree into chains so a path becomes O(log n) contiguous ranges.");

        node("centroid-decomposition", "Centroid Decomposition", "Trees", "Trees",
            after("tree-traversal", "divide-conquer"), tags("trees", "divide and conquer"), none(), 2500, 3000,
            "Recursively removing the centroid, so every path is handled at exactly one level.");

        node("dsu-on-tree", "DSU on Tree", "Trees", "Trees",
            after("dsu-small-to-large"), tags("trees", "dsu"), none(), 2300, 2700,
            "Answering subtree queries offline by keeping the heavy child's data and re-adding the rest.");

        node("virtual-tree", "Virtual / Auxiliary Trees", "Trees", "Trees",
            after("hld"), tags("trees"), none(), 2600, 3000,
            "Compressing a query's important vertices into a small tree that preserves their structure.");

        // ══ Math & Number Theory ════════════════════════════════════════════


        node("math-basics", "Arithmetic & Number Basics", "Math & Number Theory", "Math",
            after("io-basics"), tags("math"), none(), 900, 1300,
            "Overflow, integer division, and the algebra that turns a formula into one line.");

        node("gcd-lcm", "GCD, LCM & Euclid", "Math & Number Theory", "Number Theory",
            after("math-basics"), tags("number theory", "math"), none(), 1200, 1600,
            "The Euclidean algorithm and the identities built on it.");

        node("binary-exponentiation", "Binary Exponentiation", "Math & Number Theory", "Number Theory",
            after("math-basics"), tags("math", "number theory"), none(), 1300, 1700,
            "Raising to a power in log time, over integers, modulo, or any associative operation.");

        node("primes-sieve", "Primes & the Sieve", "Math & Number Theory", "Number Theory",
            after("math-basics"), tags("number theory"), none(), 1300, 1700,
            "Eratosthenes, linear sieves, and the smallest-prime-factor table.");

        node("factorization", "Integer Factorization", "Math & Number Theory", "Number Theory",
            after("primes-sieve"), tags("number theory"), none(), 1500, 1900,
            "Trial division to root n, and factoring fast in bulk with a sieve.");

        node("divisors", "Divisor Counting & Sums", "Math & Number Theory", "Number Theory",
            after("factorization"), tags("number theory"), none(), 1500, 1900,
            "Multiplicative functions over divisors, and harmonic-sum enumeration.");

        node("modular-arithmetic", "Modular Arithmetic", "Math & Number Theory", "Number Theory",
            after("gcd-lcm"), tags("number theory", "math"), none(), 1400, 1800,
            "Working under a modulus without ever letting an intermediate overflow.");

        node("modular-inverse", "Modular Inverse & Fermat", "Math & Number Theory", "Number Theory",
            after("modular-arithmetic", "binary-exponentiation"), tags("number theory"), none(), 1700, 2100,
            "Division under a prime modulus, and inverting a whole factorial table at once.");

        node("euler-totient", "Euler's Totient", "Math & Number Theory", "Number Theory",
            after("factorization", "modular-arithmetic"), tags("number theory"), none(), 1900, 2300,
            "Counting coprimes, and Euler's theorem generalising Fermat's.");

        node("crt", "Chinese Remainder Theorem", "Math & Number Theory", "Number Theory",
            after("modular-inverse"), tags("chinese remainder theorem"), none(), 2100, 2600,
            "Reassembling one congruence from several coprime ones.");

        node("matrix-exponentiation", "Matrix Exponentiation", "Math & Number Theory", "Math",
            after("binary-exponentiation"), tags("matrices"), none(), 2000, 2400,
            "Matrix multiplication under a modulus, raised to a large power.");

        node("linear-algebra-xor", "Gaussian Elimination & XOR Basis", "Math & Number Theory", "Math",
            after("xor-tricks", "matrix-exponentiation"), tags("matrices", "math"), none(), 2300, 2700,
            "Row reduction over the reals, and over GF(2) where a basis answers XOR questions.");

        node("fft", "FFT & NTT", "Math & Number Theory", "Math",
            after("modular-inverse"), tags("fft"), none(), 2500, 3000,
            "Multiplying polynomials in n log n, and the convolutions that are secretly polynomials.");

        // ══ Combinatorics & Probability ═════════════════════════════════════


        node("counting-basics", "Counting Principles", "Combinatorics & Probability", "Combinatorics",
            after("math-basics"), tags("combinatorics"), none(), 1300, 1700,
            "Sum and product rules, permutations, and counting each thing exactly once.");

        node("binomials", "Binomial Coefficients", "Combinatorics & Probability", "Combinatorics",
            after("counting-basics", "modular-inverse"), tags("combinatorics"), none(), 1500, 1900,
            "Precomputed factorials, Pascal's identity, and stars and bars.");

        node("inclusion-exclusion", "Inclusion-Exclusion", "Combinatorics & Probability", "Combinatorics",
            after("binomials"), tags("combinatorics"), none(), 1900, 2400,
            "Counting the union by alternating over intersections.");

        node("catalan-stirling", "Catalan & Stirling Numbers", "Combinatorics & Probability", "Combinatorics",
            after("binomials"), tags("combinatorics"), none(), 2100, 2500,
            "The sequences that keep appearing: balanced brackets, partitions, and set surjections.");

        node("burnside", "Burnside's Lemma & Polya", "Combinatorics & Probability", "Combinatorics",
            after("inclusion-exclusion"), tags("combinatorics", "math"), none(), 2400, 2900,
            "Counting objects up to symmetry by averaging fixed points over a group.");

        node("probability-basics", "Probability Basics", "Combinatorics & Probability", "Combinatorics",
            after("counting-basics"), tags("probabilities"), none(), 1600, 2000,
            "Sample spaces, conditioning, and independence.");

        node("expected-value", "Expected Value", "Combinatorics & Probability", "Combinatorics",
            after("probability-basics"), tags("probabilities", "math"), none(), 1900, 2300,
            "Linearity of expectation — usually the whole solution once you see it.");

        // ══ Geometry ════════════════════════════════════════════════════════


        node("geometry-basics", "Points, Vectors & Cross Products", "Geometry", "Geometry",
            after("math-basics"), tags("geometry"), none(), 1400, 1800,
            "Orientation from the sign of a cross product, and avoiding floating point where you can.");

        node("geometry-lines", "Lines, Segments & Intersection", "Geometry", "Geometry",
            after("geometry-basics"), tags("geometry"), none(), 1700, 2100,
            "Segment intersection, distances, and the degenerate cases that decide the verdict.");

        node("geometry-polygons", "Polygon Area & Point-in-Polygon", "Geometry", "Geometry",
            after("geometry-lines"), tags("geometry"), none(), 1800, 2200,
            "The shoelace formula, and ray casting with the boundary handled correctly.");

        node("convex-hull", "Convex Hull", "Geometry", "Geometry",
            after("geometry-polygons"), tags("geometry"), none(), 2000, 2500,
            "Andrew's monotone chain, and rotating calipers over the result.");

        node("geometry-sweep", "Line Sweep", "Geometry", "Geometry",
            after("geometry-lines", "ordered-sets"), tags("geometry"), tags("geometry", "sortings"), 2200, 2700,
            "Processing events left to right with an ordered set as the sweep status.");

        node("closest-pair", "Closest Pair of Points", "Geometry", "Geometry",
            after("geometry-basics", "divide-conquer"), tags("geometry"), tags("geometry", "divide and conquer"), 2200, 2600,
            "The divide-and-conquer bound, and why only a constant number of strip neighbours matter.");

        // ══ Dynamic Programming ═════════════════════════════════════════════


        node("dp-basics", "DP Basics (1D)", "Dynamic Programming", "Dynamic Programming",
            after("arrays-basics", "greedy-basics"), tags("dp"), none(), 1200, 1500,
            "State, transition and base case — and why a greedy answer was not enough.");

        node("dp-knapsack", "Knapsack", "Dynamic Programming", "Dynamic Programming",
            after("dp-basics"), tags("dp"), none(), 1400, 1800,
            "0/1, bounded and unbounded, plus the one-dimensional space optimisation.");

        node("dp-lis", "Longest Increasing Subsequence", "Dynamic Programming", "Dynamic Programming",
            after("dp-basics", "binary-search-basics"), tags("dp"), tags("dp", "binary search"), 1500, 1900,
            "The quadratic DP, and the patience-sorting version that runs in n log n.");

        node("dp-lcs", "LCS & Edit Distance", "Dynamic Programming", "Dynamic Programming",
            after("dp-basics"), tags("dp"), tags("dp", "strings"), 1500, 1900,
            "Two-sequence DP, and reconstructing the alignment rather than just its cost.");

        node("dp-grid", "DP on Grids", "Dynamic Programming", "Dynamic Programming",
            after("dp-basics"), tags("dp"), none(), 1400, 1800,
            "Two-dimensional states, and iterating them in an order where dependencies are ready.");

        node("dp-interval", "Interval DP", "Dynamic Programming", "Dynamic Programming",
            after("dp-grid"), tags("dp"), none(), 1900, 2300,
            "States over a range, solved shortest-interval-first.");

        node("dp-bitmask", "Bitmask DP", "Dynamic Programming", "Dynamic Programming",
            after("dp-grid", "bitmask-enumeration"), tags("dp"), tags("dp", "bitmasks"), 1900, 2300,
            "A subset as the state, for the problems where n is suspiciously close to twenty.");

        node("dp-tree", "DP on Trees", "Dynamic Programming", "Dynamic Programming",
            after("dp-grid", "tree-traversal"), tags("dp"), tags("dp", "trees"), 1900, 2300,
            "Combining children's answers into a parent's during one post-order pass.");

        node("dp-rerooting", "Rerooting Technique", "Dynamic Programming", "Dynamic Programming",
            after("dp-tree"), tags("dp"), tags("dp", "trees"), 2300, 2700,
            "Getting the answer for every root in linear time, from two traversals.");

        node("dp-digit", "Digit DP", "Dynamic Programming", "Dynamic Programming",
            after("dp-grid"), tags("dp"), none(), 2000, 2400,
            "Counting numbers in a range by their digits, carrying a tight-bound flag.");

        node("dp-probability", "Probability & Expectation DP", "Dynamic Programming", "Dynamic Programming",
            after("dp-grid", "probability-basics"), tags("dp"), tags("dp", "probabilities"), 2100, 2500,
            "States holding an expected value, and linearity doing most of the work.");

        node("dp-sos", "Sum over Subsets (SOS DP)", "Dynamic Programming", "Dynamic Programming",
            after("dp-bitmask"), tags("dp"), tags("dp", "bitmasks"), 2400, 2800,
            "Aggregating over every subset of every mask in n·2^n rather than 3^n.");

        node("dp-broken-profile", "Broken Profile DP", "Dynamic Programming", "Dynamic Programming",
            after("dp-bitmask"), tags("dp"), tags("dp", "bitmasks"), 2400, 2800,
            "Filling a grid cell by cell with the frontier as the mask.");

        node("dp-matrix-exponent", "DP with Matrix Exponentiation", "Dynamic Programming", "Dynamic Programming",
            after("matrix-exponentiation", "dp-basics"), tags("matrices"), none(), 2200, 2600,
            "A linear recurrence advanced 10^18 steps in log time.");

        node("dp-optimisation-cht", "Convex Hull Trick", "Dynamic Programming", "Dynamic Programming",
            after("dp-basics", "geometry-basics"), tags("dp"), tags("dp", "geometry"), 2500, 2900,
            "Transitions as lines, and querying their lower envelope instead of scanning them.");

        node("dp-optimisation-dnc", "Divide & Conquer DP", "Dynamic Programming", "Dynamic Programming",
            after("dp-interval", "divide-conquer"), tags("dp"), tags("dp", "divide and conquer"), 2500, 2900,
            "Exploiting monotone optimal split points to drop a factor of n.");

        node("dp-optimisation-knuth", "Knuth Optimisation", "Dynamic Programming", "Dynamic Programming",
            after("dp-interval"), tags("dp"), none(), 2600, 3000,
            "Bounding the split search by neighbouring answers when the quadrangle inequality holds.");

        // ══ Flows & Matching ════════════════════════════════════════════════


        node("bipartite-matching", "Bipartite Matching", "Flows & Matching", "Flows & Matching",
            after("bipartite"), tags("graph matchings"), none(), 2200, 2600,
            "Kuhn's augmenting paths, and Konig's theorem linking matching to vertex cover.");

        node("max-flow", "Maximum Flow", "Flows & Matching", "Flows & Matching",
            after("bfs", "graph-representation"), tags("flows"), none(), 2400, 2800,
            "Dinic's algorithm, and the modelling step that is harder than the algorithm.");

        node("min-cut", "Min Cut & Project Selection", "Flows & Matching", "Flows & Matching",
            after("max-flow"), tags("flows"), none(), 2500, 2900,
            "Max-flow min-cut, and reading a partition out of the residual graph.");

        node("mcmf", "Min-Cost Max-Flow", "Flows & Matching", "Flows & Matching",
            after("max-flow", "bellman-ford"), tags("flows", "graph matchings"), none(), 2600, 3000,
            "Augmenting along shortest paths by cost, with potentials to keep them non-negative.");

        // ══ Game Theory ═════════════════════════════════════════════════════


        node("game-basics", "Impartial Games & Winning States", "Game Theory", "Game Theory",
            after("dp-basics"), tags("games"), none(), 1500, 1900,
            "Win/lose states computed backwards from the terminal positions.");

        node("game-dp", "Game Theory with DP", "Game Theory", "Game Theory",
            after("game-basics", "dp-grid"), tags("games"), tags("games", "dp"), 1900, 2300,
            "Optimal play as a minimax recurrence over the state space.");

        node("nim-sprague-grundy", "Nim & Sprague-Grundy", "Game Theory", "Game Theory",
            after("game-basics", "xor-tricks"), tags("games"), none(), 2000, 2500,
            "Grundy numbers, and the XOR that combines independent games into one.");

        // ══ Techniques ══════════════════════════════════════════════════════


        node("meet-in-the-middle", "Meet in the Middle", "Techniques", "Advanced Techniques",
            after("bitmask-enumeration"), tags("meet-in-the-middle"), none(), 2100, 2600,
            "Splitting the input in half to turn 2^n into 2^(n/2), and joining the halves.");

        node("interactive", "Interactive Problems", "Techniques", "Advanced Techniques",
            after("binary-search-basics"), tags("interactive"), none(), 1500, 2000,
            "Querying a judge under a strict budget, and flushing after every write.");

        node("randomized", "Randomised Algorithms", "Techniques", "Advanced Techniques",
            after("probability-basics"), tags("probabilities"), none(), 2200, 2700,
            "Random sampling and shuffling, with a failure probability you can actually bound.");

        node("scheduling", "Scheduling Problems", "Techniques", "Advanced Techniques",
            after("greedy-basics", "heaps"), tags("schedules"), none(), 1800, 2300,
            "Deadlines, penalties and machine assignment — greedy with an exchange proof.");
    }

    /** Every node, in declaration order. */
    public static final List<NodeDef> NODES = List.copyOf(DECLARED);

    /**
     * Nodes by id. Built once at class-init rather than per call — {@code toView} used to
     * rebuild this map for every node on every request, which is 140 map constructions per
     * roadmap render.
     */
    public static final Map<String, NodeDef> BY_ID;

    /** Node ids grouped by track, in {@link #TRACKS} order. */
    public static final Map<String, List<NodeDef>> BY_TRACK;

    /** Coarse topics, derived from the nodes so the radar can never drift from the tree. */
    private static final List<String> ROLLUP_TOPICS;

    static {
        Map<String, NodeDef> byId = new LinkedHashMap<>();
        for (NodeDef n : NODES) {
            if (byId.put(n.id(), n) != null) {
                throw new IllegalStateException("Duplicate roadmap node id: " + n.id());
            }
        }
        BY_ID = Collections.unmodifiableMap(byId);

        Map<String, List<NodeDef>> byTrack = new LinkedHashMap<>();
        for (String track : TRACKS) byTrack.put(track, new ArrayList<>());
        for (NodeDef n : NODES) {
            List<NodeDef> bucket = byTrack.get(n.track());
            if (bucket == null) {
                throw new IllegalStateException(
                    "Node " + n.id() + " declares unknown track " + n.track());
            }
            bucket.add(n);
        }
        byTrack.replaceAll((k, v) -> List.copyOf(v));
        BY_TRACK = Collections.unmodifiableMap(byTrack);

        Set<String> rollups = new LinkedHashSet<>();
        for (NodeDef n : NODES) rollups.add(n.rollupTopic());
        ROLLUP_TOPICS = List.copyOf(rollups);
    }

    /** The coarse topics the radar chart and the weekly plan are grouped by. */
    public static List<String> rollupTopics() {
        return ROLLUP_TOPICS;
    }

    /** The node with this id, or null. */
    public static NodeDef byId(String id) {
        return id == null ? null : BY_ID.get(id);
    }

    /** Node ids that name this node as a prerequisite. */
    public static List<String> dependentsOf(String id) {
        List<String> out = new ArrayList<>();
        for (NodeDef n : NODES) {
            if (n.prereqIds().contains(id)) out.add(n.id());
        }
        return out;
    }
}
