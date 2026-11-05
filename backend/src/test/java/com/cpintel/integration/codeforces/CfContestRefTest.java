package com.cpintel.integration.codeforces;

import com.cpintel.exception.ApiException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.*;

/** Codeforces contest references, and the signature on manager calls. */
class CfContestRefTest {

    @Test
    @DisplayName("a group contest link keeps its group; a public link or number stays a number")
    void fromLink() {
        CfContestRef group = CfContestRef.fromLink("https://codeforces.com/group/MWSDmqGsZm/contest/612345/problem/A");
        assertEquals("MWSDmqGsZm~612345", group.encoded());
        assertEquals("/group/MWSDmqGsZm/contest/612345", group.path());
        assertTrue(group.inGroup());

        assertEquals("2259", CfContestRef.fromLink("https://codeforces.com/contest/2259").encoded());
        assertEquals("/contest/2259", CfContestRef.fromLink(" 2259 ").path());
        assertEquals("MWSDmqGsZm~612345", CfContestRef.fromLink("MWSDmqGsZm~612345").encoded(),
            "the stored form, sent back by an edit, reads as the same contest");
        assertThrows(ApiException.class, () -> CfContestRef.fromLink("https://example.com/x"));
        assertThrows(ApiException.class, () -> CfContestRef.fromLink(" "));
    }

    @Test
    @DisplayName("a stored id reads back, and anything else is refused")
    void parse() {
        assertEquals(new CfContestRef("abc", 7), CfContestRef.parse("abc~7"));
        assertEquals(new CfContestRef(null, 2259), CfContestRef.parse("2259"));
        assertThrows(ApiException.class, () -> CfContestRef.parse("1~midsem"));
        assertThrows(ApiException.class, () -> CfContestRef.parse(null));
    }

    @Test
    @DisplayName("the signature matches Codeforces' documented example")
    void signature() {
        TreeMap<String, String> params = new TreeMap<>();
        params.put("contestId", "566");
        params.put("apiKey", "xxx");
        params.put("time", "1430812800");
        assertEquals("123456c553842f15257e7480ee3c932a00e97600e5e9be136b4b746eac42f716c75b29fef6a7945c8072bf6db2cf014afe9750a2967e9adfb68bc7b292e25351679933",
            CodeforcesClient.apiSig("123456", "contest.hacks", params, "yyy"));
    }

    @Test
    @DisplayName("a key never prints its secret")
    void keyToString() {
        String shown = new CodeforcesClient.ApiKey("abcdef123", "topsecret").toString();
        assertFalse(shown.contains("topsecret"));
        assertFalse(shown.contains("abcdef123"));
    }
}
