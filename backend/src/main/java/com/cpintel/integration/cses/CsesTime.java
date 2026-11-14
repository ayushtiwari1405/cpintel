package com.cpintel.integration.cses;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/** Small parsing helpers for what the CSES API sends. */
final class CsesTime {

    /** CSES is run from Helsinki; a time sent without a zone is read as Helsinki time. */
    static final ZoneId CSES_ZONE = ZoneId.of("Europe/Helsinki");
    private static final ObjectMapper JSON = new ObjectMapper();

    private CsesTime() {}

    /**
     * A submission time. The API documents ISO 8601 but says its zone "has not been decided
     * upon", so this takes an offset when there is one and Helsinki time when there is not.
     */
    static Instant parse(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String s = raw.trim();
        try {
            return OffsetDateTime.parse(s).toInstant();
        } catch (DateTimeParseException ignored) { /* no offset */ }
        try {
            return LocalDateTime.parse(s.replace(' ', 'T')).atZone(CSES_ZONE).toInstant();
        } catch (DateTimeParseException ignored) { /* not ISO either */ }
        try {
            return LocalDateTime.parse(s, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")).atZone(CSES_ZONE).toInstant();
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /** The {@code message} of a CSES error body, or the fallback. */
    static String messageOf(String body, String fallback) {
        try {
            JsonNode n = JSON.readTree(body);
            String m = n.path("message").asText(null);
            return m == null || m.isBlank() ? fallback : m;
        } catch (Exception e) {
            return fallback;
        }
    }
}
