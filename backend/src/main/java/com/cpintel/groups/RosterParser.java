package com.cpintel.groups;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Turns a pasted roster into rows.
 *
 * <p>Two formats, because there are two ways a roster actually reaches an admin. A file exported
 * from a spreadsheet is comma-separated; a selection copied straight out of Excel, Google Sheets
 * or Numbers is <em>tab</em>-separated. Accepting only commas would reject the more common
 * gesture of the two, and reject it confusingly — the paste looks fine and every row lands in
 * one column. The delimiter is detected from the header rather than configured.
 *
 * <p>Quoting follows the usual CSV rules: a field may be wrapped in double quotes, which lets it
 * contain the delimiter, and a doubled quote inside that is a literal one. Excel writes this
 * dialect and so does every spreadsheet export, so a name like {@code "Rao, Asha"} survives.
 *
 * <p>Column names are matched loosely — case, spaces and underscores are ignored, and each field
 * has a few accepted spellings — because the header is whatever the admin's own spreadsheet
 * happened to call things, and rejecting {@code "Full Name"} in favour of {@code fullName} is a
 * pointless argument to have with someone holding a class list.
 *
 * <p>Pure and free of I/O, so every dialect quirk below is testable directly.
 */
public final class RosterParser {

    private RosterParser() {}

    /** Most rows anyone should paste at once. Guards against a runaway paste, not a real roster. */
    public static final int MAX_ROWS = 1000;

    /** Accepted spellings per field, normalised (lowercase, no spaces/underscores/hyphens). */
    private static final Map<String, Set<String>> HEADER_ALIASES = Map.of(
        "email",     Set.of("email", "emailaddress", "mail", "e"),
        "username",  Set.of("username", "user", "login", "handle", "id"),
        "fullName",  Set.of("fullname", "name", "displayname", "student", "participant"),
        "cfHandle",  Set.of("cfhandle", "codeforces", "codeforceshandle", "cf"),
        "teamName",  Set.of("teamname", "team", "domjudgeteam", "externalhandle", "djteam"),
        "djUsername", Set.of("djusername", "djuser", "djlogin", "domjudgeusername",
            "domjudgeuser", "domjudgelogin"),
        "djPassword", Set.of("djpassword", "djpass", "domjudgepassword", "domjudgepass")
    );

    /**
     * One parsed line, with whatever the header offered. Any field may be blank.
     *
     * <p>{@code djUsername} and {@code djPassword} are a DOMjudge team login to attach to the
     * account, so the admin does not have to attach each one by hand afterwards. The password
     * goes no further than the credential store: it is not echoed in any outcome.
     */
    public record Row(
        int lineNumber,
        String email,
        String username,
        String fullName,
        String cfHandle,
        String teamName,
        String djUsername,
        String djPassword
    ) {
        /** Never let a DOMjudge password reach a log line or an error body. */
        @Override
        public String toString() {
            return "Row[line=" + lineNumber + ", email=" + email + ", username=" + username
                + ", djUsername=" + djUsername + "]";
        }
    }

    /** The header could not be understood, so nothing below it can be trusted either. */
    public static final class RosterFormatException extends RuntimeException {
        public RosterFormatException(String message) { super(message); }
    }

    /**
     * Parse a pasted roster.
     *
     * @throws RosterFormatException when there is no header, no usable identity column, or more
     *         rows than {@link #MAX_ROWS}
     */
    public static List<Row> parse(String text) {
        if (text == null || text.isBlank()) return List.of();

        List<String> lines = splitLines(text);
        if (lines.isEmpty()) return List.of();

        char delimiter = detectDelimiter(lines.get(0));
        List<String> header = splitFields(lines.get(0), delimiter);
        Map<String, Integer> columns = mapColumns(header);

        // Without one of these there is no way to say who a row is about, and guessing from a
        // bare name column would silently create accounts for the wrong people.
        // A DOMjudge login counts: new accounts are named after it, so it finds them again.
        if (!columns.containsKey("email") && !columns.containsKey("username")
            && !columns.containsKey("djUsername")) {
            throw new RosterFormatException(
                "The first row has to name the columns, and needs at least an 'email', "
                    + "'username' or 'djUsername' column. Found: " + String.join(", ", header));
        }

        List<Row> rows = new ArrayList<>();
        for (int i = 1; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.isBlank()) continue;

            List<String> fields = splitFields(line, delimiter);
            Row row = new Row(
                i + 1,                                  // 1-based, counting the header
                value(fields, columns.get("email")),
                value(fields, columns.get("username")),
                value(fields, columns.get("fullName")),
                value(fields, columns.get("cfHandle")),
                value(fields, columns.get("teamName")),
                value(fields, columns.get("djUsername")),
                value(fields, columns.get("djPassword")));

            // A line of empty cells is what a spreadsheet leaves behind below the data; it is
            // not a row the admin meant to include and should not be reported as an error.
            if (row.email() == null && row.username() == null && row.fullName() == null
                && row.cfHandle() == null && row.teamName() == null
                && row.djUsername() == null && row.djPassword() == null) {
                continue;
            }
            rows.add(row);

            if (rows.size() > MAX_ROWS) {
                throw new RosterFormatException(
                    "That is more than " + MAX_ROWS + " rows. Split it into smaller batches.");
            }
        }
        return rows;
    }

    /** The column names this parser understands, for the UI to show as help. */
    public static Map<String, Set<String>> acceptedHeaders() {
        return HEADER_ALIASES;
    }

    // -- internals ---------------------------------------------------------

    /**
     * Tab or comma, whichever the header uses more.
     *
     * <p>Counted outside quotes so a header like {@code "Name, Surname",email} does not read as
     * comma-separated on the strength of a quoted comma.
     */
    private static char detectDelimiter(String headerLine) {
        int tabs = 0, commas = 0, semicolons = 0;
        boolean inQuotes = false;
        for (int i = 0; i < headerLine.length(); i++) {
            char c = headerLine.charAt(i);
            if (c == '"') inQuotes = !inQuotes;
            else if (!inQuotes) {
                if (c == '\t') tabs++;
                else if (c == ',') commas++;
                else if (c == ';') semicolons++;
            }
        }
        // Tabs win ties: a tab in a header is never incidental, whereas a comma can sit inside
        // an unquoted name. Semicolons are what a spreadsheet exports in locales where the
        // comma is the decimal separator.
        if (tabs > 0 && tabs >= commas && tabs >= semicolons) return '\t';
        if (semicolons > commas) return ';';
        return ',';
    }

    /** Split on newlines, tolerating CRLF and a UTF-8 BOM from a spreadsheet export. */
    private static List<String> splitLines(String text) {
        String cleaned = text.startsWith("﻿") ? text.substring(1) : text;
        List<String> out = new ArrayList<>();
        for (String line : cleaned.replace("\r\n", "\n").replace('\r', '\n').split("\n")) {
            out.add(line);
        }
        // Trailing blank lines carry nothing and would otherwise be reported as bad rows.
        while (!out.isEmpty() && out.get(out.size() - 1).isBlank()) out.remove(out.size() - 1);
        return out;
    }

    /** One line into fields, honouring double-quoted values and doubled inner quotes. */
    static List<String> splitFields(String line, char delimiter) {
        List<String> fields = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;

        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        current.append('"');
                        i++;
                    } else {
                        inQuotes = false;
                    }
                } else {
                    current.append(c);
                }
            } else if (c == '"') {
                inQuotes = true;
            } else if (c == delimiter) {
                fields.add(current.toString());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        fields.add(current.toString());
        return fields;
    }

    /** Header cells to field names, ignoring case, spaces, underscores and hyphens. */
    private static Map<String, Integer> mapColumns(List<String> header) {
        Map<String, Integer> columns = new LinkedHashMap<>();
        for (int i = 0; i < header.size(); i++) {
            String key = normaliseHeader(header.get(i));
            if (key.isEmpty()) continue;
            for (Map.Entry<String, Set<String>> alias : HEADER_ALIASES.entrySet()) {
                // First column wins: a sheet with two "name" columns takes the leftmost rather
                // than silently preferring whichever happens to come last.
                if (alias.getValue().contains(key) && !columns.containsKey(alias.getKey())) {
                    columns.put(alias.getKey(), i);
                }
            }
        }
        return columns;
    }

    private static String normaliseHeader(String raw) {
        return raw == null ? ""
            : raw.trim().toLowerCase(Locale.ROOT).replaceAll("[\\s_\\-]", "");
    }

    /** A trimmed cell, or null when the column is absent, short or blank. */
    private static String value(List<String> fields, Integer index) {
        if (index == null || index >= fields.size()) return null;
        String v = fields.get(index).trim();
        return v.isEmpty() ? null : v;
    }
}
