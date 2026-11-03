package com.cpintel.security;

import com.cpintel.exception.ApiException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PasswordPolicyTest {

    @ParameterizedTest
    @ValueSource(strings = {"Tr0ub4dor&3x", "Correct horse battery 9", "k7#Vq!mZ2pLw"})
    @DisplayName("a strong password passes")
    void strongPasses(String password) {
        assertEquals(List.of(), PasswordPolicy.problems(password, "ada", "ada@example.com"));
        assertDoesNotThrow(() -> PasswordPolicy.require(password, "ada", "ada@example.com"));
    }

    @Test
    @DisplayName("each missing ingredient is named")
    void namesEveryProblem() {
        List<String> p = PasswordPolicy.problems("abc", null, null);
        assertTrue(p.contains("at least 10 characters"));
        assertTrue(p.contains("an upper-case letter"));
        assertTrue(p.contains("a digit"));
        assertTrue(p.contains("a symbol, such as ! or #"));
        assertFalse(p.contains("a lower-case letter"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"Password@123", "P@ssw0rd!2024", "Qwerty#12345", "Welcome@2026"})
    @DisplayName("a decorated common password is refused")
    void commonRefused(String password) {
        assertTrue(PasswordPolicy.problems(password, null, null)
            .contains("not a commonly used password"), password);
    }

    @Test
    @DisplayName("a password built on the username or address is refused")
    void identityRefused() {
        assertTrue(PasswordPolicy.problems("Ayush#20261x", "ayush", null)
            .contains("not containing your username or email address"));
        assertTrue(PasswordPolicy.problems("Xy!9tiwari1405", "someone", "tiwari1405@example.com")
            .contains("not containing your username or email address"));
    }

    @Test
    @DisplayName("the refusal lists everything at once")
    void requireExplains() {
        ApiException e = assertThrows(ApiException.class,
            () -> PasswordPolicy.require("alllowercaseletters", null, null));
        assertTrue(e.getMessage().startsWith("Choose a stronger password"), e.getMessage());
        assertTrue(e.getMessage().contains("an upper-case letter"));
        assertTrue(e.getMessage().contains("a digit"));
    }

    @Test
    @DisplayName("empty and over-long are refused plainly")
    void bounds() {
        assertThrows(ApiException.class, () -> PasswordPolicy.require("", null, null));
        assertThrows(ApiException.class, () -> PasswordPolicy.require(null, null, null));
        assertThrows(ApiException.class,
            () -> PasswordPolicy.require("Aa1!".repeat(51), null, null));
    }
}
