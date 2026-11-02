package com.cpintel.questions;

import com.cpintel.entity.BankQuestion;

import java.util.Locale;

/**
 * Whether a program's output matches a test's answer, the way DOMjudge's default validator
 * decides it for the flags the bank writes into problem.yaml:
 *
 * <ul>
 *   <li>Output is a sequence of tokens separated by any whitespace; spacing and line breaks
 *       never matter, and the counts must agree.</li>
 *   <li>{@code EXACT} ({@code case_sensitive}): tokens equal, case included.</li>
 *   <li>{@code IGNORE_CASE} (no flags): tokens equal ignoring case.</li>
 *   <li>{@code FLOAT} ({@code float_tolerance t}): where the answer's token is a number, the
 *       output's must be a number within t of it, absolutely or relative to it; other tokens
 *       compare ignoring case.</li>
 * </ul>
 *
 * Checking a model solution with the same rule the judge will use means a question that passes
 * here judges the same way there.
 */
final class OutputChecker {

    private OutputChecker() {}

    /** Null when the output is accepted; otherwise what differs, briefly, for a person to read. */
    static String difference(String output, String answer, BankQuestion.Checker checker, Double tolerance) {
        String[] got = tokens(output);
        String[] want = tokens(answer);
        int n = Math.min(got.length, want.length);
        for (int i = 0; i < n; i++) {
            if (!same(got[i], want[i], checker, tolerance)) {
                return "token " + (i + 1) + ": expected \"" + shorten(want[i]) + "\", got \""
                    + shorten(got[i]) + "\"";
            }
        }
        if (got.length < want.length) {
            return "output ended after " + got.length + " token" + (got.length == 1 ? "" : "s")
                + "; the answer has " + want.length;
        }
        if (got.length > want.length) {
            return "output has " + (got.length - want.length) + " extra token"
                + (got.length - want.length == 1 ? "" : "s") + " after the answer's " + want.length
                + ", starting \"" + shorten(got[want.length]) + "\"";
        }
        return null;
    }

    private static boolean same(String got, String want, BankQuestion.Checker checker, Double tolerance) {
        return switch (checker) {
            case EXACT -> got.equals(want);
            case IGNORE_CASE -> got.equalsIgnoreCase(want);
            case FLOAT -> {
                Double w = number(want);
                if (w == null) yield got.equalsIgnoreCase(want);
                Double g = number(got);
                if (g == null) yield false;
                if (w.isNaN() || g.isNaN()) yield w.isNaN() && g.isNaN();
                if (w.isInfinite() || g.isInfinite()) yield w.equals(g);
                double diff = Math.abs(g - w);
                double t = tolerance == null ? 0 : tolerance;
                yield diff <= t || diff <= t * Math.abs(w);
            }
        };
    }

    private static Double number(String token) {
        if (token.isEmpty()) return null;
        char c = token.charAt(0);
        if (!(Character.isDigit(c) || c == '-' || c == '+' || c == '.')) {
            String lower = token.toLowerCase(Locale.ROOT);
            if (!lower.equals("nan") && !lower.equals("inf") && !lower.equals("infinity")) return null;
        }
        // Java would read "1d" and "2f" as numbers; a judge's strtod does not.
        char last = Character.toLowerCase(token.charAt(token.length() - 1));
        if ((last == 'd' || last == 'f') && !token.toLowerCase(Locale.ROOT).endsWith("inf")) return null;
        try {
            return Double.valueOf(token);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static final String[] NONE = new String[0];

    private static String[] tokens(String s) {
        String t = s == null ? "" : s.strip();
        return t.isEmpty() ? NONE : t.split("\\s+");
    }

    private static String shorten(String s) {
        return s.length() > 40 ? s.substring(0, 37) + "…" : s;
    }
}
