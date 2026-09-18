package com.cpintel.integration.domjudge;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;

/**
 * Parsers for the two time formats DOMjudge's API speaks.
 *
 * Neither is a standard Java can read on its own, and both appear in fields the contest clock
 * depends on, so getting them wrong shows up as a countdown that is silently hours out rather
 * than as an error. Both parsers return null on anything unrecognised instead of throwing —
 * a malformed timestamp should cost one field, not the whole contest page.
 */
public final class DjTime {

    private DjTime() {}

    /**
     * An absolute timestamp: ISO-8601 with an offset, e.g. {@code 2026-09-03T09:00:00+00:00}.
     *
     * DOMjudge always includes the offset, so {@link OffsetDateTime} is the right parser;
     * {@code Instant.parse} would reject everything that is not UTC-with-Z.
     */
    public static Instant instant(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return OffsetDateTime.parse(raw.trim()).toInstant();
        } catch (DateTimeParseException e) {
            try {
                return Instant.parse(raw.trim());
            } catch (DateTimeParseException ignored) {
                return null;
            }
        }
    }

    /**
     * A duration written {@code H:MM:SS.mmm} — or {@code -H:MM:SS.mmm} before the start.
     *
     * The hour field is not zero-padded and is not bounded by 24, so a five-hour contest is
     * {@code "5:00:00.000"} and a long one can read {@code "31:00:00.000"}. The milliseconds
     * are optional. Anything else returns null.
     */
    public static Duration duration(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String value = raw.trim();

        boolean negative = value.startsWith("-");
        if (negative || value.startsWith("+")) value = value.substring(1);

        String[] parts = value.split(":");
        if (parts.length != 3) return null;

        try {
            long hours = Long.parseLong(parts[0]);
            long minutes = Long.parseLong(parts[1]);

            String secondsPart = parts[2];
            long millis = 0;
            int dot = secondsPart.indexOf('.');
            if (dot >= 0) {
                String frac = (secondsPart.substring(dot + 1) + "000").substring(0, 3);
                millis = Long.parseLong(frac);
                secondsPart = secondsPart.substring(0, dot);
            }
            long seconds = Long.parseLong(secondsPart);

            Duration parsed = Duration.ofHours(hours)
                .plusMinutes(minutes)
                .plusSeconds(seconds)
                .plusMillis(millis);
            return negative ? parsed.negated() : parsed;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Duration in whole seconds, or {@code fallback} when it could not be read. */
    public static long seconds(String raw, long fallback) {
        Duration parsed = duration(raw);
        return parsed == null ? fallback : parsed.getSeconds();
    }
}
