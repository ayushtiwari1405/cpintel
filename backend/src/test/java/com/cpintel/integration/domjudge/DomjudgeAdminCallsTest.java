package com.cpintel.integration.domjudge;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

/** The pieces of publishing that are about DOMjudge's formats rather than its behaviour. */
class DomjudgeAdminCallsTest {

    @Test
    @DisplayName("A problem import's refusal is read out of the JSON inside its message")
    void refusal() {
        String importError = "{\"code\":400,\"message\":\"{\\\"info\\\":[],\\\"warning\\\":[],\\\"danger\\\":"
            + "[\\\"Error: problem.externalid: This value is already used.\\\"]}\"}";
        assertEquals("Error: problem.externalid: This value is already used.", DomjudgeClient.refusalOf(importError));
        assertEquals("Specified 'problem' does not exist.",
            DomjudgeClient.refusalOf("{\"code\":400,\"message\":\"Specified 'problem' does not exist.\"}"));
        assertEquals("plain text", DomjudgeClient.refusalOf("plain text"));
        assertNull(DomjudgeClient.refusalOf(""));
    }

    @Test
    @DisplayName("Durations are written as DOMjudge does")
    void clock() {
        assertEquals("5:00:00.000", DomjudgeClient.clock(Duration.ofHours(5)));
        assertEquals("2:05:09.000", DomjudgeClient.clock(Duration.ofSeconds(2 * 3600 + 5 * 60 + 9)));
        assertEquals("27:00:00.000", DomjudgeClient.clock(Duration.ofHours(27)));
    }

    @Test
    @DisplayName("Times go to the judge in whole seconds, which is all its parser accepts")
    void times() {
        assertEquals("2026-11-02T13:35:24Z",
            DomjudgeClient.judgeTime(java.time.Instant.parse("2026-11-02T13:35:24.594115403Z")));
    }
}
