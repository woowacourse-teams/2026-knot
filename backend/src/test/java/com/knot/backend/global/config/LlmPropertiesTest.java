package com.knot.backend.global.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.knot.backend.global.exception.LlmErrorCode;
import java.net.URI;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

class LlmPropertiesTest {

    @Test
    @DisplayName("기본 비활성 상태에서는 토큰과 주소 없이 시작할 수 있다")
    void validateEnabledConfiguration_success_disabled() {
        // given
        LlmProperties properties = new LlmProperties();

        // when & then
        assertThatCode(properties::validateEnabledConfiguration).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://llm.example.com", "http://127.0.0.1:1234", "http://localhost:1234"})
    @DisplayName("배포 HTTPS 주소와 로컬 테스트 HTTP 주소를 허용한다")
    void validateEnabledConfiguration_success(String url) {
        // given
        LlmProperties properties = validProperties();
        properties.setBaseUrl(URI.create(url));

        // when & then
        assertThatCode(properties::validateEnabledConfiguration).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://llm.example.com", "ftp://llm.example.com", "https://llm.example.com/v1",
            "https://user:secret@llm.example.com", "https://llm.example.com?token=secret",
            "https://llm.example.com#fragment", "/relative"})
    @DisplayName("인증값이 포함되거나 잘못된 API 기본 주소를 거절한다")
    void validateEnabledConfiguration_failure_invalidBaseUrl(String url) {
        // given
        LlmProperties properties = validProperties();
        properties.setBaseUrl(URI.create(url));

        // when & then
        assertThatThrownBy(properties::validateEnabledConfiguration)
                .hasMessage(LlmErrorCode.LLM_INVALID_CONFIGURATION.getMessage());
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", " ", "secret\nheader"})
    @DisplayName("누락된 토큰과 헤더 개행을 안전한 오류로 거절한다")
    void validateEnabledConfiguration_failure_invalidToken(String token) {
        // given
        LlmProperties properties = validProperties();
        properties.setApiToken(token);

        // when & then
        assertThatThrownBy(properties::validateEnabledConfiguration)
                .hasMessage(LlmErrorCode.LLM_INVALID_CONFIGURATION.getMessage())
                .hasNoCause();
    }

    @Test
    @DisplayName("연결 기한이 전체 요청 기한을 넘으면 시작하지 않는다")
    void validateEnabledConfiguration_failure_inconsistentTimeouts() {
        // given
        LlmProperties properties = validProperties();
        properties.setRequestTimeout(Duration.ofSeconds(1));

        // when & then
        assertThatThrownBy(properties::validateEnabledConfiguration)
                .hasMessage(LlmErrorCode.LLM_INVALID_CONFIGURATION.getMessage());
    }

    @Test
    @DisplayName("0 byte 응답 한도를 거절한다")
    void validateEnabledConfiguration_failure_invalidByteLimit() {
        // given
        LlmProperties properties = validProperties();
        properties.setMaxResponseBytes(0);

        // when & then
        assertThatThrownBy(properties::validateEnabledConfiguration)
                .hasMessage(LlmErrorCode.LLM_INVALID_CONFIGURATION.getMessage());
    }

    @Test
    void claudeRejectsIncompatibleThinkingBeforeRequests() {
        LlmProperties properties = validProperties();
        properties.setProvider("claude");
        properties.setModel("claude-sonnet-5-5");
        properties.setClaudeThinkingType("between_tools");
        assertThatCode(properties::validateEnabledConfiguration).doesNotThrowAnyException();
        properties.setClaudeThinkingType("disabled");
        assertThatThrownBy(properties::validateEnabledConfiguration)
                .hasMessage(LlmErrorCode.LLM_INVALID_CONFIGURATION.getMessage());
        properties.setModel("claude-haiku-5-5");
        assertThatCode(properties::validateEnabledConfiguration).doesNotThrowAnyException();
        properties.setClaudeThinkingType("between_tools");
        assertThatThrownBy(properties::validateEnabledConfiguration)
                .hasMessage(LlmErrorCode.LLM_INVALID_CONFIGURATION.getMessage());
    }

    private LlmProperties validProperties() {
        LlmProperties properties = new LlmProperties();
        properties.setEnabled(true);
        properties.setBaseUrl(URI.create("https://llm.example.com"));
        properties.setApiToken("test-secret");
        properties.setModel("test-model");
        return properties;
    }
}
