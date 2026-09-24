package com.cpintel.groups;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The roster parser, against the shapes a real class list actually arrives in.
 *
 * <p>The dialect matters more than it looks. An admin either exports a CSV or selects cells in a
 * spreadsheet and hits copy — and those are two different formats. Getting the second one wrong
 * fails in the worst way available: the paste looks correct, every row parses, and all the data
 * lands in the first column.
 */
class RosterParserTest {

    @Nested
    @DisplayName("delimiters")
    class Delimiters {

        @Test
        @DisplayName("reads a comma-separated export")
        void commaSeparated() {
            List<RosterParser.Row> rows = RosterParser.parse("""
                email,fullName,teamName
                asha@uni.edu,Asha Rao,Team 01
                ben@uni.edu,Ben Tan,Team 02
                """);

            assertEquals(2, rows.size());
            assertEquals("asha@uni.edu", rows.get(0).email());
            assertEquals("Asha Rao", rows.get(0).fullName());
            assertEquals("Team 01", rows.get(0).teamName());
        }

        @Test
        @DisplayName("reads a selection copied straight out of a spreadsheet")
        void tabSeparated() {
            // This is what the clipboard holds after selecting cells in Excel, Sheets or
            // Numbers. Treating it as CSV would put the whole row in the email column and then
            // create accounts with addresses like "asha@uni.edu\tAsha Rao\tTeam 01".
            List<RosterParser.Row> rows = RosterParser.parse(
                "email\tfullName\tteamName\n"
                    + "asha@uni.edu\tAsha Rao\tTeam 01\n"
                    + "ben@uni.edu\tBen Tan\tTeam 02\n");

            assertEquals(2, rows.size());
            assertEquals("asha@uni.edu", rows.get(0).email());
            assertEquals("Asha Rao", rows.get(0).fullName());
            assertEquals("Team 02", rows.get(1).teamName());
        }

        @Test
        @DisplayName("reads a semicolon export from a comma-decimal locale")
        void semicolonSeparated() {
            List<RosterParser.Row> rows = RosterParser.parse("""
                email;fullName
                asha@uni.edu;Asha Rao
                """);
            assertEquals(1, rows.size());
            assertEquals("Asha Rao", rows.get(0).fullName());
        }

        @Test
        @DisplayName("a quoted comma in the header does not make a tab file look like CSV")
        void quotedCommaDoesNotWinOverTabs() {
            List<RosterParser.Row> rows = RosterParser.parse(
                "email\t\"Name, Full\"\n"
                    + "asha@uni.edu\tAsha Rao\n");
            assertEquals(1, rows.size());
            assertEquals("asha@uni.edu", rows.get(0).email());
        }
    }

    @Nested
    @DisplayName("quoting")
    class Quoting {

        @Test
        @DisplayName("keeps a comma inside a quoted field")
        void quotedDelimiter() {
            List<RosterParser.Row> rows = RosterParser.parse("""
                email,fullName
                asha@uni.edu,"Rao, Asha"
                """);
            assertEquals("Rao, Asha", rows.get(0).fullName());
        }

        @Test
        @DisplayName("reads a doubled quote as one literal quote")
        void escapedQuote() {
            List<RosterParser.Row> rows = RosterParser.parse("""
                email,fullName
                asha@uni.edu,"Asha ""Ace"" Rao"
                """);
            assertEquals("Asha \"Ace\" Rao", rows.get(0).fullName());
        }
    }

    @Nested
    @DisplayName("headers")
    class Headers {

        @Test
        @DisplayName("accepts the spellings a real spreadsheet uses")
        void looseHeaderMatching() {
            List<RosterParser.Row> rows = RosterParser.parse("""
                E-Mail,Full Name,Codeforces,Team Name
                asha@uni.edu,Asha Rao,asha_r,Team 01
                """);

            RosterParser.Row row = rows.get(0);
            assertEquals("asha@uni.edu", row.email());
            assertEquals("Asha Rao", row.fullName());
            assertEquals("asha_r", row.cfHandle());
            assertEquals("Team 01", row.teamName());
        }

        @Test
        @DisplayName("reads a DOMjudge login, which alone is enough to identify people")
        void domjudgeLogin() {
            List<RosterParser.Row> rows = RosterParser.parse("""
                DOMjudge Username,DJ Password
                team01,pw1
                """);

            assertEquals("team01", rows.get(0).djUsername());
            assertEquals("pw1", rows.get(0).djPassword());
            assertFalse(rows.get(0).toString().contains("pw1"),
                "a row must never carry its password into a log line");
        }

        @Test
        @DisplayName("refuses a paste with no way to identify anyone")
        void requiresAnIdentityColumn() {
            // A name column alone cannot say who a row is about, and guessing would create
            // accounts for the wrong people.
            RosterParser.RosterFormatException e = assertThrows(
                RosterParser.RosterFormatException.class,
                () -> RosterParser.parse("fullName,teamName\nAsha Rao,Team 01\n"));
            assertTrue(e.getMessage().contains("email"), e.getMessage());
        }

        @Test
        @DisplayName("a username column alone is enough to identify people")
        void usernameOnlyIsAccepted() {
            List<RosterParser.Row> rows = RosterParser.parse("username\nasha_r\nben_t\n");
            assertEquals(2, rows.size());
            assertEquals("asha_r", rows.get(0).username());
        }

        @Test
        @DisplayName("the leftmost of two matching columns wins")
        void firstMatchingColumnWins() {
            List<RosterParser.Row> rows = RosterParser.parse("""
                email,name,displayName
                asha@uni.edu,First,Second
                """);
            assertEquals("First", rows.get(0).fullName());
        }
    }

    @Nested
    @DisplayName("tolerance")
    class Tolerance {

        @Test
        @DisplayName("survives CRLF line endings and a BOM")
        void windowsExport() {
            List<RosterParser.Row> rows =
                RosterParser.parse("﻿email,fullName\r\nasha@uni.edu,Asha Rao\r\n");
            assertEquals(1, rows.size());
            assertEquals("asha@uni.edu", rows.get(0).email());
        }

        @Test
        @DisplayName("skips the empty rows a spreadsheet leaves below the data")
        void skipsBlankRows() {
            List<RosterParser.Row> rows = RosterParser.parse("""
                email,fullName
                asha@uni.edu,Asha Rao

                ,,
                ben@uni.edu,Ben Tan
                """);
            assertEquals(2, rows.size());
        }

        @Test
        @DisplayName("tolerates a short row rather than losing the whole import")
        void shortRow() {
            List<RosterParser.Row> rows = RosterParser.parse("""
                email,fullName,teamName
                asha@uni.edu,Asha Rao
                """);
            assertEquals(1, rows.size());
            assertNull(rows.get(0).teamName());
        }

        @Test
        @DisplayName("reports the line number a row came from")
        void reportsLineNumbers() {
            List<RosterParser.Row> rows = RosterParser.parse("""
                email
                asha@uni.edu
                ben@uni.edu
                """);
            assertEquals(2, rows.get(0).lineNumber());
            assertEquals(3, rows.get(1).lineNumber());
        }

        @Test
        @DisplayName("empty input is not an error, just nothing to do")
        void emptyInput() {
            assertTrue(RosterParser.parse("").isEmpty());
            assertTrue(RosterParser.parse(null).isEmpty());
            assertTrue(RosterParser.parse("   \n  \n").isEmpty());
        }

        @Test
        @DisplayName("refuses a runaway paste rather than trying to import it")
        void capsRowCount() {
            StringBuilder sb = new StringBuilder("email\n");
            for (int i = 0; i < RosterParser.MAX_ROWS + 5; i++) {
                sb.append("user").append(i).append("@uni.edu\n");
            }
            assertThrows(RosterParser.RosterFormatException.class,
                () -> RosterParser.parse(sb.toString()));
        }
    }
}
