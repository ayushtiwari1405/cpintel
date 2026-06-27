package com.cpintel.roadmap;

import java.util.*;

/**
 * Defines the full roadmap node tree: sub-skills, their CF tag mappings,
 * prerequisite chains, and target difficulty bands.
 */
public class RoadmapTaxonomy {

    public record NodeDef(
        String id,
        String displayName,
        String parentTopic,      // canonical TopicMastery topic this rolls up into
        List<String> prereqIds,  // node ids that must be UNLOCKED/COMPLETED first
        List<String> cfTags,     // Codeforces tags used to pull problems
        int minDifficulty,
        int maxDifficulty,
        int orderIndex
    ) {}

    public static final List<NodeDef> NODES = List.of(

        // ── Arrays & fundamentals ──────────────────────────────
        new NodeDef("arrays-basics", "Array Basics", "Arrays",
            List.of(), List.of("implementation", "arrays"), 800, 1200, 1),
        new NodeDef("two-pointers", "Two Pointers", "Two Pointers",
            List.of("arrays-basics"), List.of("two pointers"), 1100, 1500, 2),
        new NodeDef("sliding-window", "Sliding Window", "Two Pointers",
            List.of("two-pointers"), List.of("two pointers", "brute force"), 1200, 1600, 3),
        new NodeDef("prefix-sums", "Prefix Sums", "Arrays",
            List.of("arrays-basics"), List.of("implementation"), 1000, 1400, 4),
        new NodeDef("sorting", "Sorting Algorithms", "Arrays",
            List.of("arrays-basics"), List.of("sortings"), 900, 1300, 5),

        // ── Strings ─────────────────────────────────────────────
        new NodeDef("strings-basics", "String Basics", "Strings",
            List.of(), List.of("strings", "implementation"), 800, 1200, 6),
        new NodeDef("string-hashing", "String Hashing", "Strings",
            List.of("strings-basics"), List.of("hashing", "strings"), 1500, 1900, 7),
        new NodeDef("kmp-zfunction", "KMP / Z-function", "Strings",
            List.of("string-hashing"), List.of("strings", "string suffix structures"), 1700, 2100, 8),
        new NodeDef("tries", "Tries", "Tries",
            List.of("strings-basics"), List.of("trie", "strings"), 1500, 2000, 9),

        // ── Binary search ───────────────────────────────────────
        new NodeDef("binary-search-basics", "Binary Search Basics", "Binary Search",
            List.of("arrays-basics"), List.of("binary search"), 1100, 1500, 10),
        new NodeDef("binary-search-answer", "Binary Search on Answer", "Binary Search",
            List.of("binary-search-basics"), List.of("binary search"), 1500, 1900, 11),

        // ── Greedy ──────────────────────────────────────────────
        new NodeDef("greedy-basics", "Greedy Basics", "Greedy",
            List.of("arrays-basics", "sorting"), List.of("greedy"), 1000, 1400, 12),
        new NodeDef("greedy-advanced", "Advanced Greedy", "Greedy",
            List.of("greedy-basics"), List.of("greedy", "constructive algorithms"), 1500, 1900, 13),

        // ── Graphs ──────────────────────────────────────────────
        new NodeDef("graph-basics", "Graph Representation", "Graphs",
            List.of(), List.of("graphs"), 1000, 1400, 14),
        new NodeDef("dfs-bfs", "DFS & BFS", "Graphs",
            List.of("graph-basics"), List.of("dfs and similar", "graphs"), 1200, 1600, 15),
        new NodeDef("shortest-paths", "Shortest Paths (Dijkstra/Bellman-Ford)", "Graphs",
            List.of("dfs-bfs"), List.of("shortest paths", "graphs"), 1600, 2000, 16),
        new NodeDef("union-find", "Union-Find / DSU", "Graphs",
            List.of("graph-basics"), List.of("dsu", "graphs"), 1400, 1800, 17),
        new NodeDef("mst", "Minimum Spanning Tree", "Graphs",
            List.of("union-find"), List.of("graphs", "dsu"), 1700, 2100, 18),
        new NodeDef("flows-matching", "Flows & Matching", "Graphs",
            List.of("shortest-paths"), List.of("flows", "graph matchings"), 2200, 2700, 19),

        // ── Trees ───────────────────────────────────────────────
        new NodeDef("tree-basics", "Tree Traversal Basics", "Trees",
            List.of("dfs-bfs"), List.of("trees", "dfs and similar"), 1300, 1700, 20),
        new NodeDef("lca", "LCA & Tree Queries", "Binary Lifting",
            List.of("tree-basics"), List.of("trees", "graphs"), 1700, 2100, 21),
        new NodeDef("binary-lifting", "Binary Lifting", "Binary Lifting",
            List.of("lca"), List.of("trees", "data structures"), 1800, 2200, 22),
        new NodeDef("segment-trees", "Segment Trees", "Segment Trees",
            List.of("tree-basics", "prefix-sums"), List.of("data structures"), 1700, 2200, 23),
        new NodeDef("segment-trees-advanced", "Segment Trees with Lazy Propagation", "Segment Trees",
            List.of("segment-trees"), List.of("data structures"), 2000, 2500, 24),

        // ── DP ──────────────────────────────────────────────────
        new NodeDef("dp-basics", "DP Basics (1D)", "Dynamic Programming",
            List.of("arrays-basics", "greedy-basics"), List.of("dp"), 1200, 1600, 25),
        new NodeDef("dp-2d", "DP on Grids / 2D", "Dynamic Programming",
            List.of("dp-basics"), List.of("dp"), 1500, 1900, 26),
        new NodeDef("dp-bitmask", "Bitmask DP", "Dynamic Programming",
            List.of("dp-2d"), List.of("dp", "bitmasks"), 1900, 2300, 27),
        new NodeDef("dp-trees", "DP on Trees", "Dynamic Programming",
            List.of("dp-2d", "tree-basics"), List.of("dp", "trees"), 1900, 2400, 28),
        new NodeDef("digit-dp", "Digit DP", "Dynamic Programming",
            List.of("dp-2d"), List.of("dp", "number theory"), 2000, 2500, 29),

        // ── Number theory ───────────────────────────────────────
        new NodeDef("number-theory-basics", "Number Theory Basics", "Number Theory",
            List.of(), List.of("number theory", "math"), 1000, 1400, 30),
        new NodeDef("modular-arithmetic", "Modular Arithmetic", "Number Theory",
            List.of("number-theory-basics"), List.of("number theory", "math"), 1400, 1800, 31),
        new NodeDef("combinatorics", "Combinatorics", "Number Theory",
            List.of("modular-arithmetic"), List.of("combinatorics", "math"), 1500, 1900, 32),

        // ── Bit manipulation ────────────────────────────────────
        new NodeDef("bit-manipulation", "Bit Manipulation", "Bit Manipulation",
            List.of("arrays-basics"), List.of("bitmasks"), 1100, 1500, 33),

        // ── Geometry ─────────────────────────────────────────────
        new NodeDef("geometry-basics", "Geometry Basics", "Geometry",
            List.of("number-theory-basics"), List.of("geometry"), 1400, 1800, 34),
        new NodeDef("convex-hull", "Convex Hull", "Geometry",
            List.of("geometry-basics"), List.of("geometry"), 1900, 2400, 35)
    );

    public static Map<String, NodeDef> byId() {
        Map<String, NodeDef> map = new LinkedHashMap<>();
        for (NodeDef n : NODES) map.put(n.id(), n);
        return map;
    }
}
