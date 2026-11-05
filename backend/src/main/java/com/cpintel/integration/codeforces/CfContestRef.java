package com.cpintel.integration.codeforces;

import com.cpintel.exception.ApiException;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A Codeforces contest, public or inside a group.
 *
 * <p>A group contest lives under its group's address ({@code /group/<code>/contest/<id>}) and
 * is visible only to the group's members, so its group code has to travel with it. Stored as
 * {@code <code>~<id>}, the same shape a DOMjudge event id has; a public contest stays a bare
 * number, so everything stored before groups were supported reads back unchanged.
 */
public record CfContestRef(String group, int id) {

    private static final char SEPARATOR = '~';
    private static final Pattern GROUP_LINK =
        Pattern.compile("group/([A-Za-z0-9]{1,40})/contests?/(\\d{1,9})");
    private static final Pattern PUBLIC_LINK =
        Pattern.compile("(?:contests?|gym)/(\\d{1,9})|^\\s*(\\d{1,9})\\s*$");
    private static final Pattern STORED =
        Pattern.compile("^\\s*([A-Za-z0-9]{1,40})~(\\d{1,9})\\s*$");

    /** From what an admin pastes: a contest or group-contest link, a bare number, or a stored ref. */
    public static CfContestRef fromLink(String raw) {
        if (raw == null || raw.isBlank()) {
            throw ApiException.badRequest("Paste a Codeforces contest link.");
        }
        Matcher group = GROUP_LINK.matcher(raw);
        if (group.find()) return new CfContestRef(group.group(1), Integer.parseInt(group.group(2)));
        Matcher stored = STORED.matcher(raw);
        if (stored.matches()) return new CfContestRef(stored.group(1), Integer.parseInt(stored.group(2)));
        Matcher plain = PUBLIC_LINK.matcher(raw.trim());
        if (plain.find()) {
            return new CfContestRef(null, Integer.parseInt(plain.group(1) != null ? plain.group(1) : plain.group(2)));
        }
        throw ApiException.badRequest("That does not look like a Codeforces contest link. Expected "
            + "something like https://codeforces.com/contest/2259 or "
            + "https://codeforces.com/group/abc123/contest/612345");
    }

    /** From a stored id: a bare number, or {@code <group>~<id>}. */
    public static CfContestRef parse(String stored) {
        if (stored != null) {
            Matcher m = STORED.matcher(stored);
            if (m.matches()) return new CfContestRef(m.group(1), Integer.parseInt(m.group(2)));
            try {
                return new CfContestRef(null, Integer.parseInt(stored.trim()));
            } catch (NumberFormatException ignored) {
                // falls through to the refusal
            }
        }
        throw ApiException.badRequest("'" + stored + "' is not a Codeforces contest id.");
    }

    public boolean inGroup() {
        return group != null;
    }

    /** What is stored and routed: the bare number, or {@code <group>~<id>}. */
    public String encoded() {
        return inGroup() ? group + SEPARATOR + id : String.valueOf(id);
    }

    /** The contest's path on codeforces.com, without a trailing slash. */
    public String path() {
        return inGroup() ? "/group/" + group + "/contest/" + id : "/contest/" + id;
    }

    @Override
    public String toString() {
        return encoded();
    }
}
