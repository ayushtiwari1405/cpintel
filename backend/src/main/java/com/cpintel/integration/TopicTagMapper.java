package com.cpintel.integration;

import org.springframework.stereotype.Component;
import java.util.*;
import java.util.stream.Collectors;

@Component
public class TopicTagMapper {

    public static final Map<String, String> TAG_MAP_STATIC = new HashMap<>();

    static {
        TAG_MAP_STATIC.put("arrays",                 "Arrays");
        TAG_MAP_STATIC.put("array",                  "Arrays");
        TAG_MAP_STATIC.put("data structures",        "Arrays");
        TAG_MAP_STATIC.put("strings",                "Strings");
        TAG_MAP_STATIC.put("string",                 "Strings");
        TAG_MAP_STATIC.put("hashing",                "Strings");
        TAG_MAP_STATIC.put("binary search",          "Binary Search");
        TAG_MAP_STATIC.put("binary-search",          "Binary Search");
        TAG_MAP_STATIC.put("two pointers",           "Two Pointers");
        TAG_MAP_STATIC.put("two-pointers",           "Two Pointers");
        TAG_MAP_STATIC.put("greedy",                 "Greedy");
        TAG_MAP_STATIC.put("dp",                     "Dynamic Programming");
        TAG_MAP_STATIC.put("dynamic programming",    "Dynamic Programming");
        TAG_MAP_STATIC.put("dynamic-programming",    "Dynamic Programming");
        TAG_MAP_STATIC.put("graphs",                 "Graphs");
        TAG_MAP_STATIC.put("graph",                  "Graphs");
        TAG_MAP_STATIC.put("dfs and similar",        "Graphs");
        TAG_MAP_STATIC.put("bfs",                    "Graphs");
        TAG_MAP_STATIC.put("shortest paths",         "Graphs");
        TAG_MAP_STATIC.put("trees",                  "Trees");
        TAG_MAP_STATIC.put("tree",                   "Trees");
        TAG_MAP_STATIC.put("dfs",                    "Trees");
        TAG_MAP_STATIC.put("segment tree",           "Segment Trees");
        TAG_MAP_STATIC.put("segment-tree",           "Segment Trees");
        TAG_MAP_STATIC.put("binary lifting",         "Binary Lifting");
        TAG_MAP_STATIC.put("lca",                    "Binary Lifting");
        TAG_MAP_STATIC.put("number theory",          "Number Theory");
        TAG_MAP_STATIC.put("math",                   "Number Theory");
        TAG_MAP_STATIC.put("mathematics",            "Number Theory");
        TAG_MAP_STATIC.put("combinatorics",          "Number Theory");
        TAG_MAP_STATIC.put("bitmasks",               "Bit Manipulation");
        TAG_MAP_STATIC.put("bit manipulation",       "Bit Manipulation");
        TAG_MAP_STATIC.put("bit-manipulation",       "Bit Manipulation");
        TAG_MAP_STATIC.put("trie",                   "Tries");
        TAG_MAP_STATIC.put("tries",                  "Tries");
        TAG_MAP_STATIC.put("geometry",               "Geometry");
        TAG_MAP_STATIC.put("computational geometry", "Geometry");
        TAG_MAP_STATIC.put("sortings",               "Arrays");
        TAG_MAP_STATIC.put("sorting",                "Arrays");
        TAG_MAP_STATIC.put("implementation",         "Arrays");
        TAG_MAP_STATIC.put("brute force",            "Arrays");
        TAG_MAP_STATIC.put("constructive algorithms","Greedy");
        TAG_MAP_STATIC.put("divide and conquer",     "Binary Search");
        TAG_MAP_STATIC.put("graph matchings",        "Graphs");
        TAG_MAP_STATIC.put("flows",                  "Graphs");
        TAG_MAP_STATIC.put("string suffix structures","Strings");
        TAG_MAP_STATIC.put("expression parsing",     "Strings");
    }

    public static final List<String> CANONICAL_TOPICS = List.of(
        "Arrays", "Strings", "Binary Search", "Two Pointers", "Greedy",
        "Dynamic Programming", "Graphs", "Trees", "Segment Trees",
        "Binary Lifting", "Number Theory", "Bit Manipulation", "Tries", "Geometry"
    );

    public Set<String> normalize(List<String> tags) {
        if (tags == null) return Set.of();
        return tags.stream()
            .map(t -> TAG_MAP_STATIC.getOrDefault(t.toLowerCase(), null))
            .filter(Objects::nonNull)
            .collect(Collectors.toSet());
    }

    public boolean isCanonical(String topic) {
        return CANONICAL_TOPICS.contains(topic);
    }
}
