package com.cpintel.evaluation;

import java.util.Comparator;
import java.util.Locale;

/**
 * Usernames in natural order: runs of digits compare as numbers, everything else ignores case.
 *
 * <p>Roll numbers are how a class is split between TAs, and plain string order puts {@code cs10}
 * before {@code cs2}. Here {@code cs2 < cs10 < cs100}, and fixed-width roll numbers
 * ({@code 22BCS001}) come out the same either way.
 */
public final class UsernameOrder {

    private UsernameOrder() {}

    public static final Comparator<String> NATURAL = UsernameOrder::compare;

    public static int compare(String a, String b) {
        String x = a.toLowerCase(Locale.ROOT);
        String y = b.toLowerCase(Locale.ROOT);
        int i = 0, j = 0;
        while (i < x.length() && j < y.length()) {
            char cx = x.charAt(i), cy = y.charAt(j);
            if (Character.isDigit(cx) && Character.isDigit(cy)) {
                int si = i, sj = j;
                while (i < x.length() && Character.isDigit(x.charAt(i))) i++;
                while (j < y.length() && Character.isDigit(y.charAt(j))) j++;
                String nx = stripZeros(x.substring(si, i));
                String ny = stripZeros(y.substring(sj, j));
                if (nx.length() != ny.length()) return nx.length() - ny.length();
                int c = nx.compareTo(ny);
                if (c != 0) return c;
                // Equal numbers: fewer leading zeros first, so the order is still total.
                int z = (i - si) - (j - sj);
                if (z != 0) return z;
            } else {
                if (cx != cy) return cx - cy;
                i++;
                j++;
            }
        }
        return (x.length() - i) - (y.length() - j);
    }

    /** Whether a username falls in [from, to]. A null end is open. */
    public static boolean inRange(String username, String from, String to) {
        if (username == null) return false;
        if (from != null && compare(username, from) < 0) return false;
        return to == null || compare(username, to) <= 0;
    }

    private static String stripZeros(String digits) {
        int k = 0;
        while (k < digits.length() - 1 && digits.charAt(k) == '0') k++;
        return digits.substring(k);
    }
}
