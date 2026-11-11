package com.knot.backend.global.config;

import com.knot.backend.global.exception.LlmErrorCode;
import com.knot.backend.global.exception.LlmException;
import java.net.URI;
import java.time.Duration;
import java.util.Set;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties(prefix = "knot.llm")
public class LlmProperties {
    private static final Set<String> LOOPBACK_HOSTS = Set.of(
            "localhost",
            "127.0.0.1",
            "[::1]"
    );

    private boolean enabled;
    private String provider = "lm-studio";
    private URI baseUrl;
    private String apiToken;
    private String model;
    private String claudeThinkingType = "between_tools";
    private Duration connectTimeout = Duration.ofSeconds(3);
    private Duration requestTimeout = Duration.ofSeconds(120);
    private long maxResponseBytes = 262144;

    public void validateEnabledConfiguration() {
        if (!enabled) {
            return;
        }
        validateProvider();
        if ("claude".equals(provider) && (claudeThinkingType == null || !Set.of(
                "disabled",
                "adaptive",
                "between_tools"
        )
                .contains(claudeThinkingType))) {
            throw invalidConfiguration();
        }
        validateBaseUrl();
        validateCredentials();
        validateTimeouts();
        validateResponseLimit();
    }

    private void validateProvider() {
        if (provider == null || !Set.of(
                "lm-studio",
                "claude"
        )
                .contains(provider)) {
            throw invalidConfiguration();
        }
    }

    private void validateBaseUrl() {
        if (baseUrl == null) {
            throw invalidConfiguration();
        }
        if (baseUrl.getHost() == null) {
            throw invalidConfiguration();
        }
        validateScheme();
        validateBaseUrlComponents();
        validateBaseUrlPath();
    }

    private void validateBaseUrlComponents() {
        if (baseUrl.getUserInfo() != null) {
            throw invalidConfiguration();
        }
        if (baseUrl.getQuery() != null) {
            throw invalidConfiguration();
        }
        if (baseUrl.getFragment() != null) {
            throw invalidConfiguration();
        }
    }

    private void validateBaseUrlPath() {
        String path = baseUrl.getPath();
        if (path == null) {
            return;
        }
        if (path.isEmpty()) {
            return;
        }
        if (!path.equals("/")) {
            throw invalidConfiguration();
        }
    }

    private void validateScheme() {
        if ("https".equals(baseUrl.getScheme())) {
            return;
        }
        if ("http".equals(baseUrl.getScheme()) && isLoopbackHost()) {
            return;
        }
        throw invalidConfiguration();
    }

    private boolean isLoopbackHost() {
        String host = baseUrl.getHost();
        return LOOPBACK_HOSTS.contains(host);
    }

    private void validateCredentials() {
        validateCredentialText(apiToken);
        validateTokenLineBreaks();
        validateCredentialText(model);
    }

    private void validateCredentialText(String value) {
        if (value == null) {
            throw invalidConfiguration();
        }
        if (value.isBlank()) {
            throw invalidConfiguration();
        }
    }

    private void validateTokenLineBreaks() {
        if (apiToken.contains("\r")) {
            throw invalidConfiguration();
        }
        if (apiToken.contains("\n")) {
            throw invalidConfiguration();
        }
    }

    private void validateTimeouts() {
        validatePositiveDuration(connectTimeout);
        validatePositiveDuration(requestTimeout);
        if (connectTimeout.compareTo(requestTimeout) > 0) {
            throw invalidConfiguration();
        }
    }

    private void validatePositiveDuration(Duration duration) {
        if (duration == null) {
            throw invalidConfiguration();
        }
        if (duration.isNegative()) {
            throw invalidConfiguration();
        }
        if (duration.isZero()) {
            throw invalidConfiguration();
        }
        try {
            if (duration.toMillis() < 1) {
                throw invalidConfiguration();
            }
        } catch (ArithmeticException exception) {
            throw invalidConfiguration(exception);
        }
    }

    private void validateResponseLimit() {
        if (maxResponseBytes <= 0) {
            throw invalidConfiguration();
        }
    }

    private LlmException invalidConfiguration() {
        return new LlmException(LlmErrorCode.LLM_INVALID_CONFIGURATION);
    }

    private LlmException invalidConfiguration(Throwable cause) {
        return new LlmException(
                LlmErrorCode.LLM_INVALID_CONFIGURATION,
                cause
        );
    }
}
