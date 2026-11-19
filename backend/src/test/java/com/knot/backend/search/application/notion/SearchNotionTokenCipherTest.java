package com.knot.backend.search.application.notion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.knot.backend.global.config.SearchNotionProperties;
import com.knot.backend.search.domain.SearchErrorCode;
import com.knot.backend.search.domain.SearchException;
import java.util.Base64;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SearchNotionTokenCipherTest {
    private static final String KEY = "eKElXGj4+A3yala4lakBsWUXqUkIiYFZqCrEx84y2cM=";

    @Test
    @DisplayName("암호화한 토큰을 같은 키로 풀면 원래 값이 나오고 저장 값에는 원문이 없다")
    void encrypt_roundTrip() {
        // given
        SearchNotionTokenCipher cipher = cipher(KEY);

        // when
        String stored = cipher.encrypt("access-token-원문");

        // then
        assertThat(stored).doesNotContain("access-token");
        assertThat(cipher.decrypt(stored)).isEqualTo("access-token-원문");
    }

    @Test
    @DisplayName("같은 값도 암호화할 때마다 저장 값이 달라진다")
    void encrypt_randomIv() {
        // given
        SearchNotionTokenCipher cipher = cipher(KEY);

        // when
        String first = cipher.encrypt("token");
        String second = cipher.encrypt("token");

        // then
        assertThat(first).isNotEqualTo(second);
    }

    @Test
    @DisplayName("저장 값이 바뀌면 풀지 않고 상태 오류로 막는다")
    void decrypt_tampered() {
        // given
        SearchNotionTokenCipher cipher = cipher(KEY);
        byte[] bytes = Base64.getDecoder()
                .decode(cipher.encrypt("token"));
        bytes[bytes.length - 1] ^= 1;
        String tampered = Base64.getEncoder()
                .encodeToString(bytes);

        // when & then
        assertThatThrownBy(() -> cipher.decrypt(tampered)).isInstanceOf(SearchException.class)
                .extracting("errorCode")
                .isEqualTo(SearchErrorCode.INVALID_SEARCH_STATE);
    }

    @Test
    @DisplayName("다른 키로 암호화한 값은 풀지 않는다")
    void decrypt_otherKey() {
        // given
        String stored = cipher(KEY).encrypt("token");
        String otherKey = Base64.getEncoder()
                .encodeToString(new byte[32]);

        // when & then
        assertThatThrownBy(() -> cipher(otherKey).decrypt(stored)).isInstanceOf(SearchException.class);
    }

    @Test
    @DisplayName("키를 넣지 않으면 서버는 뜨지만 암호화할 때 설정 오류로 막는다")
    void encrypt_missingKey() {
        // given
        SearchNotionTokenCipher cipher = cipher(null);

        // when & then
        assertThatThrownBy(() -> cipher.encrypt("token")).isInstanceOf(SearchException.class)
                .extracting("errorCode")
                .isEqualTo(SearchErrorCode.INVALID_SEARCH_CONFIGURATION);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"not-base64!", "c2hvcnQ="})
    @DisplayName("32바이트 base64가 아닌 키는 띄울 때 설정 오류로 막는다")
    void create_invalidKey(String key) {
        // when & then
        assertThatThrownBy(() -> cipher(key)).isInstanceOf(SearchException.class)
                .extracting("errorCode")
                .isEqualTo(SearchErrorCode.INVALID_SEARCH_CONFIGURATION);
    }

    private SearchNotionTokenCipher cipher(String key) {
        SearchNotionProperties properties = new SearchNotionProperties();
        properties.setTokenEncryptionKey(key);
        return new SearchNotionTokenCipher(properties);
    }
}
