package com.cpintel.roadmap;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Normalises judge tags into the vocabulary the skill tree is written against.
 *
 * <p>Only Codeforces is left, and its tags are the tree's own, so this is a clean-up pass rather
 * than a translation. It used to also map LeetCode and CodeChef tags onto Codeforces ones; both
 * judges were dropped, and the mapping went with them. The class stays the one place a new
 * judge's vocabulary would be translated.
 */
public final class PlatformTagVocabulary {

    private PlatformTagVocabulary() {}

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

    /** Lowercase, trimmed, internal whitespace collapsed. */
    private static String clean(String tag) {
        if (tag == null) return "";
        return tag.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }
}
