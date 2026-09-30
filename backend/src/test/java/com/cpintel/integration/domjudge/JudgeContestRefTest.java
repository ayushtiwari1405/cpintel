package com.cpintel.integration.domjudge;

import com.cpintel.exception.ApiException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The classroom-qualified contest id every DOMjudge lookup is keyed on. A mistake here would
 * route one classroom's submission to another's judge, so the edges are pinned.
 */
class JudgeContestRefTest {

    @Test
    @DisplayName("round-trips classroom and contest")
    void roundTrip() {
        JudgeContestRef ref = JudgeContestRef.parse("12~nwerc18");
        assertEquals(12L, ref.classroomId());
        assertEquals("nwerc18", ref.contestId());
        assertEquals("12~nwerc18", ref.encoded());
        assertEquals("12~nwerc18", JudgeContestRef.encode(12, "nwerc18"));
    }

    @Test
    @DisplayName("a bare id is not a reference — it does not say which judge")
    void bareIsRefused() {
        assertNull(JudgeContestRef.tryParse("nwerc18"));
        assertThrows(ApiException.class, () -> JudgeContestRef.parse("nwerc18"));
    }

    @Test
    @DisplayName("malformed references are refused rather than half-parsed")
    void malformed() {
        assertNull(JudgeContestRef.tryParse("~demo"));
        assertNull(JudgeContestRef.tryParse("3~"));
        assertNull(JudgeContestRef.tryParse("x~demo"));
        assertNull(JudgeContestRef.tryParse("3~a~b"));
        assertNull(JudgeContestRef.tryParse(null));
    }

    @Test
    @DisplayName("the judge's own id is shown whichever form is stored")
    void judgeIdOf() {
        assertEquals("demo", JudgeContestRef.judgeIdOf("3~demo"));
        assertEquals("2259", JudgeContestRef.judgeIdOf("2259"));
    }

    @Test
    @DisplayName("qualify adds the classroom for DOMjudge and leaves Codeforces alone")
    void qualify() {
        assertEquals("3~demo", JudgeContestRef.qualify("DOMJUDGE", 3L, " demo "));
        assertEquals("3~demo", JudgeContestRef.qualify("DOMJUDGE", 3L, "3~demo"));
        assertEquals("2259", JudgeContestRef.qualify("CODEFORCES", 3L, "2259"));
    }

    @Test
    @DisplayName("qualify refuses another classroom's contest")
    void qualifyRefusesOtherClassroom() {
        assertThrows(ApiException.class, () -> JudgeContestRef.qualify("DOMJUDGE", 3L, "4~demo"));
    }
}
