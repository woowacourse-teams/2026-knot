package com.knot.backend.global.config;

import com.knot.backend.search.domain.SearchException;
import com.knot.backend.search.domain.SearchErrorCode;
import java.net.URI;
import java.time.Duration;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties(prefix = "knot.search.agent")
public class SearchAgentProperties {

    private boolean enabled;
    private String signingKey;
    private URI mcpBaseUrl = URI.create("http://127.0.0.1:8080");
    private Duration budget = Duration.ofSeconds(120);
    private Duration cancellationPollInterval = Duration.ofMillis(250);
    private int toolCallLimit = 12;
    private SearchRetrievalMode retrievalMode = SearchRetrievalMode.AGENT;
    private int readCodePoints = 6000;
    private int contextCharacterLimit = 48000;

    public void validate() {
        if (signingKey == null || signingKey.length() < 32) {
            throw new SearchException(SearchErrorCode.INVALID_SEARCH_CONFIGURATION);
        }
        if (budget == null || budget.isNegative() || budget.isZero()) {
            throw new SearchException(SearchErrorCode.INVALID_SEARCH_CONFIGURATION);
        }
        if (toolCallLimit < 1 || toolCallLimit > 100) {
            throw new SearchException(SearchErrorCode.INVALID_SEARCH_CONFIGURATION);
        }
        if (readCodePoints < 1 || readCodePoints > 12000) {
            throw new SearchException(SearchErrorCode.INVALID_SEARCH_CONFIGURATION);
        }
        if (contextCharacterLimit < readCodePoints || contextCharacterLimit > 200000) {
            throw new SearchException(SearchErrorCode.INVALID_SEARCH_CONFIGURATION);
        }
        validateRetrievalMode();
        validateOrigin();
        validateCancellationInterval();
    }

    private void validateRetrievalMode() {
        if (retrievalMode == null) {
            throw new SearchException(SearchErrorCode.INVALID_SEARCH_CONFIGURATION);
        }
    }

    private void validateCancellationInterval() {
        if (cancellationPollInterval == null || cancellationPollInterval.toMillis() < 1) {
            throw new SearchException(SearchErrorCode.INVALID_SEARCH_CONFIGURATION);
        }
    }

    private void validateOrigin() {
        if (mcpBaseUrl == null || mcpBaseUrl.getHost() == null || mcpBaseUrl.getUserInfo() != null) {
            throw new SearchException(SearchErrorCode.INVALID_SEARCH_CONFIGURATION);
        }
        if (mcpBaseUrl.getQuery() != null || mcpBaseUrl.getFragment() != null) {
            throw new SearchException(SearchErrorCode.INVALID_SEARCH_CONFIGURATION);
        }
        String path = mcpBaseUrl.getPath();
        if (path != null && !path.isEmpty() && !path.equals("/")) {
            throw new SearchException(SearchErrorCode.INVALID_SEARCH_CONFIGURATION);
        }
        if ("https".equals(mcpBaseUrl.getScheme())) {
            return;
        }
        if ("http".equals(mcpBaseUrl.getScheme()) && "127.0.0.1".equals(mcpBaseUrl.getHost())) {
            return;
        }
        throw new SearchException(SearchErrorCode.INVALID_SEARCH_CONFIGURATION);
    }
}
