package com.knot.backend.search.application.notion;

import com.knot.backend.global.config.SearchNotionProperties;
import com.knot.backend.search.domain.SearchErrorCode;
import com.knot.backend.search.domain.SearchException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/**
 * 노션 토큰과 PKCE verifier를 AES-256-GCM으로 암호화한다. 저장 값은 base64(IV 12바이트 + 암호문과 태그)다.
 */
@Component
public class SearchNotionTokenCipher {
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int KEY_BYTES = 32;
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    public SearchNotionTokenCipher(SearchNotionProperties properties) {
        this.key = parseKey(properties.getTokenEncryptionKey());
    }

    public String encrypt(String plain) {
        byte[] iv = new byte[IV_BYTES];
        random.nextBytes(iv);
        byte[] encrypted = run(
                Cipher.ENCRYPT_MODE,
                iv,
                plain.getBytes(StandardCharsets.UTF_8)
        );
        return Base64.getEncoder()
                .encodeToString(
                        ByteBuffer.allocate(iv.length + encrypted.length)
                                .put(iv)
                                .put(encrypted)
                                .array()
                );
    }

    public String decrypt(String stored) {
        byte[] bytes = decode(stored);
        if (bytes.length <= IV_BYTES) {
            throw new SearchException(SearchErrorCode.INVALID_SEARCH_STATE);
        }
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        byte[] iv = new byte[IV_BYTES];
        buffer.get(iv);
        byte[] encrypted = new byte[buffer.remaining()];
        buffer.get(encrypted);
        return new String(
                run(
                        Cipher.DECRYPT_MODE,
                        iv,
                        encrypted
                ),
                StandardCharsets.UTF_8
        );
    }

    private byte[] run(
            int mode,
            byte[] iv,
            byte[] input
    ) {
        if (key == null) {
            throw new SearchException(SearchErrorCode.INVALID_SEARCH_CONFIGURATION);
        }
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(
                    mode,
                    key,
                    new GCMParameterSpec(
                            TAG_BITS,
                            iv
                    )
            );
            return cipher.doFinal(input);
        } catch (GeneralSecurityException exception) {
            throw new SearchException(SearchErrorCode.INVALID_SEARCH_STATE);
        }
    }

    private static byte[] decode(String stored) {
        try {
            return Base64.getDecoder()
                    .decode(stored);
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw new SearchException(SearchErrorCode.INVALID_SEARCH_STATE);
        }
    }

    /**
     * 키가 비어 있으면 노션 연결을 쓰기 전까지 서버를 띄울 수 있게 null로 두고, 형식이 틀리면 바로 실패한다.
     */
    private static SecretKeySpec parseKey(String encoded) {
        if (encoded == null || encoded.isBlank()) {
            return null;
        }
        byte[] bytes = decodeKey(encoded);
        if (bytes.length != KEY_BYTES) {
            throw new SearchException(SearchErrorCode.INVALID_SEARCH_CONFIGURATION);
        }
        return new SecretKeySpec(
                bytes,
                "AES"
        );
    }

    private static byte[] decodeKey(String encoded) {
        try {
            return Base64.getDecoder()
                    .decode(encoded.strip());
        } catch (IllegalArgumentException exception) {
            throw new SearchException(SearchErrorCode.INVALID_SEARCH_CONFIGURATION);
        }
    }
}
