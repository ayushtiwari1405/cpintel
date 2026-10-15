package com.cpintel.common;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * What is stored in place of a bearer token: its SHA-256, in hex.
 *
 * <p>For the random tokens CPIntel hands out — password-reset links and refresh tokens — not for
 * passwords. A token is long and random, so a fast hash is enough: there is nothing to guess, and
 * what the digest buys is that a dump of the table holds nothing that can be presented. A
 * password a person chose needs Argon2id instead, because that one can be guessed.
 */
public final class TokenDigest {

    private TokenDigest() {}

    public static String of(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(token == null ? new byte[0] : token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
