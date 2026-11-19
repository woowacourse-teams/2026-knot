package com.knot.backend.search.application.notion;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * OAuth state와 PKCE(S256) 값을 만든다.
 */
final class NotionOAuthSecrets {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int RANDOM_BYTES = 32;

    private NotionOAuthSecrets() {}

    static String random() {
        byte[] bytes = new byte[RANDOM_BYTES];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(bytes);
    }

    static String hash(String state) {
        return HexFormat.of()
                .formatHex(sha256(state));
    }

    static String challenge(String verifier) {
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(sha256(verifier));
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.US_ASCII));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(
                    "SHA-256 unavailable",
                    exception
            );
        }
    }
}
