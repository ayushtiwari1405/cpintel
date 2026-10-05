package com.cpintel.evaluation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class UsernameOrderTest {

    @Test
    @DisplayName("Digits compare as numbers, letters ignore case")
    void natural() {
        List<String> names = new ArrayList<>(List.of("cs10", "CS2", "cs1", "cs100", "ab", "cs02"));
        names.sort(UsernameOrder.NATURAL);
        assertEquals(List.of("ab", "cs1", "CS2", "cs02", "cs10", "cs100"), names);
    }

    @Test
    @DisplayName("Fixed-width roll numbers keep their order")
    void rollNumbers() {
        assertTrue(UsernameOrder.compare("22BCS009", "22BCS010") < 0);
        assertTrue(UsernameOrder.compare("22BCS099", "23BCS001") < 0);
        assertTrue(UsernameOrder.compare("22bcs010", "22BCS010") == 0);
    }

    @Test
    @DisplayName("A range includes both ends, and an empty end is open")
    void ranges() {
        assertTrue(UsernameOrder.inRange("cs5", "cs1", "cs10"));
        assertTrue(UsernameOrder.inRange("cs1", "cs1", "cs10"));
        assertTrue(UsernameOrder.inRange("cs10", "cs1", "cs10"));
        assertFalse(UsernameOrder.inRange("cs11", "cs1", "cs10"));
        assertFalse(UsernameOrder.inRange("cs0", "cs1", "cs10"));
        assertTrue(UsernameOrder.inRange("zz", "cs1", null));
        assertTrue(UsernameOrder.inRange("aa", null, "cs10"));
        assertTrue(UsernameOrder.inRange("anyone", null, null));
    }
}
