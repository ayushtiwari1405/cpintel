package com.cpintel.labs;

import com.cpintel.exception.ApiException;

import java.net.URI;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A lab problem as the admin typed it, reduced to the platform and the key a submission names.
 *
 * <p>Accepted forms:
 * <ul>
 *   <li>Codeforces: {@code codeforces.com/problemset/problem/1700/A},
 *       {@code codeforces.com/contest/1700/problem/A}, {@code codeforces.com/gym/102000/problem/B},
 *       or the shorthand {@code 1700A} / {@code CF 1700A}.</li>
 *   <li>LeetCode: {@code leetcode.com/problems/two-sum/} (any suffix such as
 *       {@code /description/}), or {@code lc:two-sum}.</li>
 * </ul>
 * The key is what a submission is matched on: {@code "1700/A"} for Codeforces (the contest the
 * problem was set in, as Codeforces reports it), the slug for LeetCode.
 */
public record ProblemRef(String platform, String key) {

    public static final String CODEFORCES = "CODEFORCES";
    public static final String LEETCODE = "LEETCODE";
    public static final String CSES = "CSES";

    private static final Pattern CF_PATH = Pattern.compile(
        "^/(?:problemset/problem/(\\d+)/([A-Za-z]\\d?)|(?:contest|gym)/(\\d+)/problem/([A-Za-z]\\d?))/?$");
    private static final Pattern CF_SHORT = Pattern.compile("^(?:cf\\s*:?\\s*)?(\\d{1,6})\\s*([A-Za-z]\\d?)$",
        Pattern.CASE_INSENSITIVE);
    private static final Pattern LC_PATH = Pattern.compile("^/problems/([a-z0-9-]+)(?:/.*)?$");
    private static final Pattern CSES_PATH = Pattern.compile("^/problemset/(?:task|view|submit|stats|result)/(\\d{1,6})/?$");
    private static final Pattern CSES_SHORT = Pattern.compile("^cses\\s*:?\\s*(\\d{1,6})$", Pattern.CASE_INSENSITIVE);
    private static final Pattern LC_SHORT = Pattern.compile("^lc\\s*:\\s*([a-z0-9-]+)$", Pattern.CASE_INSENSITIVE);

    public static ProblemRef codeforces(int contestId, String index) {
        return new ProblemRef(CODEFORCES, contestId + "/" + index.toUpperCase(Locale.ROOT));
    }

    public static ProblemRef parse(String raw) {
        String input = raw == null ? "" : raw.trim();
        if (input.isEmpty()) throw ApiException.badRequest("Paste a Codeforces, LeetCode or CSES problem link.");

        Matcher shortCf = CF_SHORT.matcher(input);
        if (shortCf.matches()) return codeforces(Integer.parseInt(shortCf.group(1)), shortCf.group(2));
        Matcher shortCses = CSES_SHORT.matcher(input);
        if (shortCses.matches()) return new ProblemRef(CSES, String.valueOf(Integer.parseInt(shortCses.group(1))));
        Matcher shortLc = LC_SHORT.matcher(input);
        if (shortLc.matches()) return new ProblemRef(LEETCODE, shortLc.group(1).toLowerCase(Locale.ROOT));

        URI uri;
        try {
            uri = URI.create(input.contains("://") ? input : "https://" + input);
        } catch (IllegalArgumentException e) {
            throw unrecognised(input);
        }
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        String path = uri.getPath() == null ? "" : uri.getPath();

        if (host.equals("codeforces.com") || host.endsWith(".codeforces.com")) {
            Matcher m = CF_PATH.matcher(path);
            if (m.matches()) {
                return m.group(1) != null
                    ? codeforces(Integer.parseInt(m.group(1)), m.group(2))
                    : codeforces(Integer.parseInt(m.group(3)), m.group(4));
            }
        } else if (host.equals("cses.fi") || host.equals("www.cses.fi")) {
            Matcher m = CSES_PATH.matcher(path);
            if (m.matches()) return new ProblemRef(CSES, String.valueOf(Integer.parseInt(m.group(1))));
        } else if (host.equals("leetcode.com") || host.equals("www.leetcode.com")) {
            Matcher m = LC_PATH.matcher(path.toLowerCase(Locale.ROOT));
            if (m.matches()) return new ProblemRef(LEETCODE, m.group(1));
        }
        throw unrecognised(input);
    }

    public int cfContestId() {
        return Integer.parseInt(key.substring(0, key.indexOf('/')));
    }

    public String cfIndex() {
        return key.substring(key.indexOf('/') + 1);
    }

    /** Gym contests have ids from 100000 up and are not in the public problemset. */
    public boolean isGym() {
        return CODEFORCES.equals(platform) && cfContestId() >= 100_000;
    }

    public String url() {
        if (LEETCODE.equals(platform)) return "https://leetcode.com/problems/" + key + "/";
        if (CSES.equals(platform)) return "https://cses.fi/problemset/task/" + key + "/";
        return isGym()
            ? "https://codeforces.com/gym/" + cfContestId() + "/problem/" + cfIndex()
            : "https://codeforces.com/problemset/problem/" + cfContestId() + "/" + cfIndex();
    }

    private static ApiException unrecognised(String input) {
        return ApiException.badRequest("\"" + input + "\" is not a Codeforces, LeetCode or CSES problem link. "
            + "Use e.g. https://codeforces.com/problemset/problem/1700/A, "
            + "https://leetcode.com/problems/two-sum/ or https://cses.fi/problemset/task/1068/.");
    }
}
