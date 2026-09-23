package com.cpintel.common;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * CPIntel's own names for programming languages, and how to recognise a judge's.
 *
 * <h2>Why this has to exist</h2>
 *
 * <p>Every party in this system names languages differently, and none of them agree. Codeforces
 * names <em>compilers</em> — {@code "GNU G++20 13.2 (64 bit, winlibs)"}, {@code "PyPy 3.10
 * (7.3.15, 64bit)"} — and renames them as it upgrades. DOMjudge uses short ids that vary per
 * installation ({@code cpp}, {@code c}, {@code py3}, {@code python3}, {@code kt}). The local
 * runner has its own ids, because it is running a toolchain on this machine rather than talking
 * to anybody.
 *
 * <p>An administrator saying "this examination is C++ and Python only" is not talking about any
 * of those. They are talking about languages. So the allow-list on an examination is stored in
 * the ids below, and everything else is mapped onto them at the edge — which is what lets one
 * decision hold across two judges and a local compiler without the admin knowing any of their
 * vocabularies.
 *
 * <p>The ids are deliberately the same strings the local runner uses for the languages it can
 * run ({@code cpp}, {@code python3}). One namespace rather than two, so an allow-list can gate
 * the Run button and the Submit button with the same set and they cannot drift.
 *
 * <h2>Unrecognised languages</h2>
 *
 * <p>{@link #classify} answers null when it cannot place something, and callers treat that as
 * <em>not allowed</em> whenever a restriction is in force. That is the fail-closed direction
 * and it is the right one: an administrator who listed three languages meant three, and
 * offering a fourth because a pattern here did not match would quietly overrule them. The cost
 * is that an exotic language on somebody's DOMjudge build is unavailable in a restricted
 * examination until a rule is added — which is visible, recoverable by clearing the
 * restriction, and much better than the alternative.
 */
public final class Languages {

    private Languages() {}

    /** One language, as an admin picks it. */
    public record Known(String id, String label) {}

    /**
     * Everything an examination may be restricted to.
     *
     * <p>Wider than what the local runner can execute, on purpose: an examination sat on
     * DOMjudge may perfectly well be Java-only, and the Run button simply being unavailable for
     * it is a smaller problem than not being able to set the rule. Ordered as the admin screen
     * shows them — the ones people actually sit examinations in first.
     */
    public static final List<Known> CATALOG = List.of(
        new Known("cpp",        "C++"),
        new Known("python3",    "Python 3"),
        new Known("java",       "Java"),
        new Known("c",          "C"),
        new Known("csharp",     "C#"),
        new Known("kotlin",     "Kotlin"),
        new Known("go",         "Go"),
        new Known("rust",       "Rust"),
        new Known("javascript", "JavaScript"),
        new Known("ruby",       "Ruby"),
        new Known("scala",      "Scala"),
        new Known("php",        "PHP"),
        new Known("pascal",     "Pascal"),
        new Known("haskell",    "Haskell"),
        new Known("ocaml",      "OCaml"),
        new Known("sql",        "SQL")
    );

    private static final Map<String, String> LABELS = CATALOG.stream()
        .collect(java.util.stream.Collectors.toUnmodifiableMap(Known::id, Known::label));

    private static final Set<String> IDS = LABELS.keySet();

    /**
     * Judge ids that map straight through, checked before any pattern matching.
     *
     * <p>These are short, exact and ambiguous in a way patterns handle badly — DOMjudge's
     * {@code "c"} would match nothing sensible as a word, and {@code "py3"} matches no English
     * word at all. Matching them as whole ids first also stops {@code "c"} being caught by a
     * looser rule later.
     */
    private static final Map<String, String> BY_ID = Map.ofEntries(
        Map.entry("cpp", "cpp"), Map.entry("c++", "cpp"), Map.entry("cc", "cpp"),
        Map.entry("gpp", "cpp"), Map.entry("cxx", "cpp"),
        Map.entry("c", "c"),
        Map.entry("py3", "python3"), Map.entry("python3", "python3"),
        Map.entry("py", "python3"), Map.entry("python", "python3"),
        Map.entry("pypy3", "python3"), Map.entry("pypy", "python3"),
        Map.entry("java", "java"),
        Map.entry("kt", "kotlin"), Map.entry("kotlin", "kotlin"),
        Map.entry("cs", "csharp"), Map.entry("csharp", "csharp"),
        Map.entry("go", "go"),
        Map.entry("rs", "rust"), Map.entry("rust", "rust"),
        Map.entry("js", "javascript"), Map.entry("javascript", "javascript"),
        Map.entry("node", "javascript"),
        Map.entry("rb", "ruby"), Map.entry("ruby", "ruby"),
        Map.entry("scala", "scala"),
        Map.entry("php", "php"),
        Map.entry("pas", "pascal"), Map.entry("pascal", "pascal"),
        Map.entry("hs", "haskell"), Map.entry("haskell", "haskell"),
        Map.entry("ml", "ocaml"), Map.entry("ocaml", "ocaml"),
        Map.entry("sql", "sql")
    );

    /**
     * Label patterns, in order — the first match wins, so the specific ones come first.
     *
     * <p>Mirrors the editor's own classifier, which has been reading Codeforces' compiler names
     * for long enough to have the ordering traps written into it. Two of them matter: C must be
     * tried after C++, because "GNU G++" contains no word that says C but a loose C rule would
     * claim it; and JavaScript must be tried before Java, because "JavaScript" contains "Java".
     */
    private static final List<Rule> RULES = List.of(
        rule("cpp",        "\\bG\\+\\+|\\bclang\\+\\+|\\bC\\+\\+"),
        rule("c",          "\\bGCC C\\d|\\bGNU GCC\\b|\\bC1\\d\\b|\\bANSI C\\b"),
        // "CPython 3.11" is a label a judge may reasonably use, and a bare \bPython\b does
        // not match it — the word boundary falls before the C.
        rule("python3",    "\\bPyPy\\b|\\bC?Python\\b"),
        rule("kotlin",     "\\bKotlin\\b"),
        rule("javascript", "\\bJavaScript\\b|\\bNode\\.?js\\b"),
        rule("java",       "\\bJava\\b"),
        rule("csharp",     "\\bC#|\\bMono\\b|\\.NET\\b"),
        rule("rust",       "\\bRust\\b"),
        rule("go",         "\\bGo\\b|\\bGolang\\b"),
        rule("ruby",       "\\bRuby\\b"),
        rule("scala",      "\\bScala\\b"),
        rule("haskell",    "\\bHaskell\\b|\\bGHC\\b"),
        rule("pascal",     "\\bPascal\\b|\\bDelphi\\b|\\bFPC\\b"),
        rule("php",        "\\bPHP\\b"),
        rule("ocaml",      "\\bOCaml\\b"),
        rule("sql",        "\\bSQL\\b|\\bSQLite\\b")
    );

    private record Rule(String id, Pattern pattern) {}

    private static Rule rule(String id, String regex) {
        return new Rule(id, Pattern.compile(regex, Pattern.CASE_INSENSITIVE));
    }

    // ------------------------------------------------------------------ api

    public static boolean isKnown(String id) {
        return id != null && IDS.contains(id.trim().toLowerCase(Locale.ROOT));
    }

    /** The display label for one of our ids, or the id itself if it is not one of ours. */
    public static String labelFor(String id) {
        if (id == null) return "";
        return LABELS.getOrDefault(id.trim().toLowerCase(Locale.ROOT), id);
    }

    /**
     * Which of our languages a judge's option is, or null if we cannot tell.
     *
     * <p>The id is tried first because it is the more stable of the two — a judge renames its
     * compiler labels far more often than its ids — and the label second, because Codeforces
     * has no ids worth the name and its labels are all there is.
     *
     * @param judgeId the judge's own identifier, which may be null
     * @param label   the judge's display name, which may be null
     */
    public static String classify(String judgeId, String label) {
        if (judgeId != null) {
            String direct = BY_ID.get(judgeId.trim().toLowerCase(Locale.ROOT));
            if (direct != null) return direct;
        }
        String matched = match(label);
        // An id that is no use as a whole word may still read as one in a pattern —
        // "gnu-c++" is nobody's exact id but says C++ plainly enough.
        return matched != null ? matched : match(judgeId);
    }

    private static String match(String text) {
        if (text == null || text.isBlank()) return null;
        for (Rule r : RULES) {
            if (r.pattern().matcher(text).find()) return r.id();
        }
        return null;
    }
}
