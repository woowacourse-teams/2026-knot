package com.knot.backend.global.config;

import com.knot.backend.document.domain.DocumentErrorCode;
import com.knot.backend.document.domain.DocumentException;
import jakarta.annotation.PostConstruct;
import java.time.Duration;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties(prefix = "knot.document-generation.categories")
public class DocumentCategoryProperties {

    private boolean enabled;
    private int maxRetries = 1;
    private Duration retryBackoff = Duration.ofSeconds(5);
    private Duration requestTimeout = Duration.ofSeconds(20);
    private Duration totalBudget = Duration.ofSeconds(45);
    private int maxInputCharacters = 40000;

    @PostConstruct
    public void validate() {
        validateRetries();
        validateDuration(retryBackoff);
        validateDuration(requestTimeout);
        validateDuration(totalBudget);
        validateTotalBudget();
        validateInputLimit();
    }

    private void validateRetries() {
        if (maxRetries < 0 || maxRetries > 3) {
            throw invalidConfiguration();
        }
    }

    private void validateTotalBudget() {
        if (totalBudget.compareTo(Duration.ofMinutes(5)) > 0) {
            throw invalidConfiguration();
        }
    }

    private void validateInputLimit() {
        if (maxInputCharacters < 1000) {
            throw invalidConfiguration();
        }
    }

    private void validateDuration(Duration duration) {
        if (duration == null || duration.isNegative() || duration.isZero()) {
            throw invalidConfiguration();
        }
    }

    private DocumentException invalidConfiguration() {
        return new DocumentException(DocumentErrorCode.INVALID_GENERATION_WORKER_CONFIGURATION);
    }
}
