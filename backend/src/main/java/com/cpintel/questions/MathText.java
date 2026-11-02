package com.cpintel.questions;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lifts the TeX out of a Markdown statement before Markdown sees it, and says where it was.
 *
 * <p>Markdown would otherwise read {@code $a_i * b_i$} as emphasis and eat the underscores. Each
 * formula is swapped for a placeholder made of private-use characters, which Markdown passes
 * through as plain text, and put back as a drawn formula once the HTML exists.
 *
 * <p>The rules are pandoc's: {@code $…$} on one line, opening {@code $} not followed by a space,
 * closing {@code $} not preceded by one nor followed by a digit (so "$5 and $6" stays money);
 * {@code $$…$$} for a displayed formula, which may span lines; {@code \$} for a dollar sign.
 * Fenced code blocks and inline code spans are left exactly as written.
 */
final class MathText {

    static final char OPEN = '';
    static final char CLOSE = '';
    static final Pattern PLACEHOLDER = Pattern.compile(OPEN + "(\\d+)" + CLOSE);

    record Formula(String tex, boolean display) {}

    record Lifted(String markdown, List<Formula> formulas) {}

    private MathText() {}

    static Lifted lift(String source) {
        // A placeholder typed by the author would be taken for one of ours.
        String text = source.replace(String.valueOf(OPEN), "").replace(String.valueOf(CLOSE), "");
        StringBuilder out = new StringBuilder(text.length());
        List<Formula> formulas = new ArrayList<>();
        int i = 0;
        int n = text.length();
        boolean fenced = false;
        String fence = null;
        boolean lineStart = true;
        while (i < n) {
            if (lineStart) {
                int end = text.indexOf('\n', i);
                String line = text.substring(i, end < 0 ? n : end);
                String marker = fenceMarker(line);
                if (fenced || marker != null) {
                    if (!fenced) {
                        fenced = true;
                        fence = marker;
                    } else if (marker != null && marker.charAt(0) == fence.charAt(0)
                        && marker.length() >= fence.length() && line.strip().length() == marker.length()) {
                        fenced = false;
                    }
                    out.append(line);
                    if (end < 0) break;
                    out.append('\n');
                    i = end + 1;
                    continue;
                }
            }
            char c = text.charAt(i);
            lineStart = false;
            if (c == '\n') {
                out.append(c);
                i++;
                lineStart = true;
                continue;
            }
            if (c == '\\' && i + 1 < n && text.charAt(i + 1) == '$') {
                out.append("\\$");
                i += 2;
                continue;
            }
            if (c == '`') {
                int run = runOf(text, i, '`');
                int close = findRun(text, i + run, '`', run);
                if (close < 0) {
                    out.append(text, i, i + run);
                    i += run;
                } else {
                    out.append(text, i, close + run);
                    i = close + run;
                }
                continue;
            }
            if (c == '$') {
                if (i + 1 < n && text.charAt(i + 1) == '$') {
                    int close = text.indexOf("$$", i + 2);
                    if (close > i + 2) {
                        String tex = text.substring(i + 2, close).strip();
                        if (!tex.isEmpty()) {
                            out.append(placeholder(formulas, tex, true));
                            i = close + 2;
                            continue;
                        }
                    }
                    out.append("$$");
                    i += 2;
                    continue;
                }
                int close = inlineClose(text, i);
                if (close > 0) {
                    out.append(placeholder(formulas, text.substring(i + 1, close), false));
                    i = close + 1;
                    continue;
                }
            }
            out.append(c);
            i++;
        }
        return new Lifted(out.toString(), formulas);
    }

    /** The closing {@code $} of an inline formula opened at {@code open}, or -1. */
    private static int inlineClose(String text, int open) {
        int n = text.length();
        if (open + 1 >= n || Character.isWhitespace(text.charAt(open + 1))) return -1;
        for (int j = open + 1; j < n; j++) {
            char c = text.charAt(j);
            if (c == '\n') return -1;
            if (c == '\\') {
                j++;
                continue;
            }
            if (c == '$') {
                if (Character.isWhitespace(text.charAt(j - 1))) continue;
                if (j + 1 < n && Character.isDigit(text.charAt(j + 1))) continue;
                return j;
            }
        }
        return -1;
    }

    private static String placeholder(List<Formula> formulas, String tex, boolean display) {
        formulas.add(new Formula(tex, display));
        return OPEN + Integer.toString(formulas.size() - 1) + CLOSE;
    }

    /** The backticks or tildes opening (or closing) a fenced block on this line, if any. */
    private static String fenceMarker(String line) {
        int indent = 0;
        while (indent < line.length() && indent < 4 && line.charAt(indent) == ' ') indent++;
        if (indent > 3 || indent >= line.length()) return null;
        char c = line.charAt(indent);
        if (c != '`' && c != '~') return null;
        int run = runOf(line, indent, c);
        return run >= 3 ? line.substring(indent, indent + run) : null;
    }

    private static int runOf(String s, int from, char c) {
        int j = from;
        while (j < s.length() && s.charAt(j) == c) j++;
        return j - from;
    }

    /** The start of the next run of exactly {@code len} {@code c}s at or after {@code from}. */
    private static int findRun(String s, int from, char c, int len) {
        int j = from;
        while (j < s.length()) {
            if (s.charAt(j) == c) {
                int run = runOf(s, j, c);
                if (run == len) return j;
                j += run;
            } else {
                j++;
            }
        }
        return -1;
    }

    /** Puts something back in each placeholder of {@code html}. */
    static String restore(String html, java.util.function.IntFunction<String> formula) {
        Matcher m = PLACEHOLDER.matcher(html);
        StringBuilder out = new StringBuilder(html.length());
        while (m.find()) {
            m.appendReplacement(out, Matcher.quoteReplacement(formula.apply(Integer.parseInt(m.group(1)))));
        }
        m.appendTail(out);
        return out.toString();
    }
}
