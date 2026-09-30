package com.cpintel.integration.domjudge;

import com.cpintel.exception.ApiException;

/**
 * A DOMjudge contest as CPIntel names it: the classroom whose judge runs it, and the judge's own
 * contest id. Written {@code "<classroom>~<contest>"}, e.g. {@code "3~demo"}.
 *
 * <p><b>Why the classroom travels inside the id.</b> A DOMjudge contest id is only unique on its
 * own instance — two judges will both have a {@code demo} — and a deployment now talks to one
 * judge per classroom. Everything CPIntel keys on {@code (platform, contestId)} (events, the
 * submission archive, file rules, exam sessions, the arena's URLs) therefore needs the judge as
 * part of that key. Qualifying the id once, at the edge, makes every one of those lookups
 * correct without each having to learn about classrooms; only the code that actually talks to
 * DOMjudge splits it again.
 *
 * <p>{@code ~} is outside DOMjudge's id alphabet ({@code [A-Za-z0-9_.-]}), so the split is never
 * ambiguous, and it is unreserved in URLs, so the qualified id needs no escaping in a path.
 */
public record JudgeContestRef(long classroomId, String contestId) {

    public static final char SEPARATOR = '~';

    public JudgeContestRef {
        if (contestId == null || contestId.isBlank()) {
            throw ApiException.badRequest("A DOMjudge contest id is required.");
        }
        contestId = contestId.trim();
    }

    /** Parses a qualified id, refusing a bare one — a bare id no longer says which judge. */
    public static JudgeContestRef parse(String ref) {
        JudgeContestRef parsed = tryParse(ref);
        if (parsed == null) {
            throw ApiException.badRequest("'" + ref + "' does not say which classroom's judge "
                + "it is on. DOMjudge contests are named <classroom>~<contest>.");
        }
        return parsed;
    }

    /** The parsed reference, or null when {@code ref} is not a qualified id. */
    public static JudgeContestRef tryParse(String ref) {
        if (ref == null) return null;
        int at = ref.indexOf(SEPARATOR);
        if (at <= 0 || at == ref.length() - 1) return null;
        try {
            long classroomId = Long.parseLong(ref.substring(0, at).trim());
            String contestId = ref.substring(at + 1).trim();
            if (contestId.isEmpty() || contestId.indexOf(SEPARATOR) >= 0) return null;
            return new JudgeContestRef(classroomId, contestId);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public static String encode(long classroomId, String contestId) {
        return new JudgeContestRef(classroomId, contestId).encoded();
    }

    /** The judge's own id for display, whether or not {@code ref} is qualified. */
    public static String judgeIdOf(String ref) {
        JudgeContestRef parsed = tryParse(ref);
        return parsed == null ? ref : parsed.contestId();
    }

    /**
     * The id an event stores: qualified with its classroom for DOMjudge, as it stands otherwise.
     *
     * <p>Accepts either form, so an edit that sends back what it was given does not read as a
     * change of contest — but refuses a qualified id from another classroom.
     */
    public static String qualify(String platform, Long classroomId, String raw) {
        String id = raw == null ? "" : raw.trim();
        if (!"DOMJUDGE".equals(platform)) return id;
        JudgeContestRef ref = tryParse(id);
        if (ref != null) {
            if (ref.classroomId() != classroomId) {
                throw ApiException.badRequest("Contest " + id + " is on another classroom's "
                    + "judge.");
            }
            return ref.encoded();
        }
        return encode(classroomId, id);
    }

    public String encoded() {
        return classroomId + String.valueOf(SEPARATOR) + contestId;
    }

    @Override
    public String toString() {
        return encoded();
    }
}
