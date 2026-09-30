package com.cpintel.common;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * AES-256-GCM under a key from the environment, for the few secrets CPIntel has to be able to
 * read back: DOMjudge logins, classroom service accounts, examination passwords and codes, and
 * Codeforces sessions.
 *
 * <p><b>Every value says which key sealed it.</b> A sealed value is {@code v1:} followed by
 * base64 of a 12-byte IV and the ciphertext. The prefix is what makes rotating a key possible
 * later: a second key becomes {@code v2}, both are kept while old values are re-sealed, and
 * nothing has to guess which key a value belongs to. Values written before the prefix existed
 * carry none and are read as {@code v1}, which is the key they were sealed under.
 *
 * <p>Not a Spring bean: each owner builds one from its own key, so the keys stay separate and
 * losing one does not expose the others.
 */
public final class SecretBox {

    /** The version written today. */
    public static final String CURRENT = "v1";

    private static final int GCM_TAG_BITS = 128;
    private static final int IV_LENGTH = 12;

    private final SecretKey key;
    private final SecureRandom random = new SecureRandom();

    public SecretBox(String secret) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalArgumentException("A SecretBox needs a key");
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(secret.getBytes(StandardCharsets.UTF_8));
            this.key = new SecretKeySpec(digest, "AES");
        } catch (Exception e) {
            throw new IllegalStateException("Cannot derive an encryption key", e);
        }
    }

    public String seal(String plain) {
        try {
            byte[] iv = new byte[IV_LENGTH];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] ct = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));

            byte[] out = new byte[iv.length + ct.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(ct, 0, out, iv.length, ct.length);
            return CURRENT + ":" + Base64.getEncoder().encodeToString(out);
        } catch (Exception e) {
            // No detail from the cause: it could otherwise echo what was being sealed.
            throw new IllegalStateException("Could not encrypt a secret");
        }
    }

    /**
     * The plain value.
     *
     * @throws IllegalStateException when the value was sealed under another key, or under a
     *         version this build does not know — the owner decides what that means to it
     */
    public String open(String sealed) {
        if (sealed == null) throw new IllegalStateException("Nothing to decrypt");
        String body = sealed;
        int colon = sealed.indexOf(':');
        if (colon > 0) {
            String version = sealed.substring(0, colon);
            if (!CURRENT.equals(version)) {
                throw new IllegalStateException("Unknown secret version " + version);
            }
            body = sealed.substring(colon + 1);
        }
        try {
            byte[] raw = Base64.getDecoder().decode(body);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key,
                new GCMParameterSpec(GCM_TAG_BITS, raw, 0, IV_LENGTH));
            return new String(cipher.doFinal(raw, IV_LENGTH, raw.length - IV_LENGTH),
                StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("Could not decrypt a secret");
        }
    }
}
