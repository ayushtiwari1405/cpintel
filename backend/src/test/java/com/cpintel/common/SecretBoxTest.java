package com.cpintel.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

/** Every sealed value names its key version, so a key can be rotated later. */
class SecretBoxTest {

    @Test
    @DisplayName("round-trips, and marks what it writes as v1")
    void roundTrip() {
        SecretBox box = new SecretBox("key-one");
        String sealed = box.seal("hunter2");
        assertTrue(sealed.startsWith("v1:"));
        assertNotEquals(box.seal("hunter2"), sealed, "a fresh IV each time");
        assertEquals("hunter2", box.open(sealed));
    }

    @Test
    @DisplayName("reads values written before the version marker existed")
    void readsUnmarked() throws Exception {
        byte[] key = MessageDigest.getInstance("SHA-256")
            .digest("key-one".getBytes(StandardCharsets.UTF_8));
        byte[] iv = new byte[12];
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
        byte[] ct = cipher.doFinal("legacy".getBytes(StandardCharsets.UTF_8));
        byte[] out = new byte[iv.length + ct.length];
        System.arraycopy(ct, 0, out, iv.length, ct.length);

        assertEquals("legacy", new SecretBox("key-one").open(Base64.getEncoder().encodeToString(out)));
    }

    @Test
    @DisplayName("refuses another key's value, and a version it does not know")
    void refuses() {
        String sealed = new SecretBox("key-one").seal("x");
        assertThrows(IllegalStateException.class, () -> new SecretBox("key-two").open(sealed));
        assertThrows(IllegalStateException.class,
            () -> new SecretBox("key-one").open("v9:" + sealed.substring(3)));
    }
}
