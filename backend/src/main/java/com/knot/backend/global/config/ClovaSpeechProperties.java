package com.knot.backend.global.config;

import com.knot.backend.recording.domain.RecordingErrorCode;
import com.knot.backend.recording.domain.RecordingException;
import java.net.URI;
import java.util.Set;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties(prefix = "knot.clova-speech")
public class ClovaSpeechProperties {
    private URI invokeUrl;
    private String apiKey;

    public void validate() {
        if (invokeUrl == null || invokeUrl.getHost() == null || invokeUrl.getUserInfo() != null
                || invokeUrl.getQuery() != null || invokeUrl.getFragment() != null || apiKey == null || apiKey.isBlank()
                || apiKey.contains("\r") || apiKey.contains("\n")) {
            throw invalid();
        }
        if ("https".equals(invokeUrl.getScheme())) {
            return;
        }
        if ("http".equals(invokeUrl.getScheme()) && Set.of(
                "localhost",
                "127.0.0.1",
                "[::1]"
        )
                .contains(invokeUrl.getHost())) {
            return;
        }
        throw invalid();
    }

    private RecordingException invalid() {
        return new RecordingException(RecordingErrorCode.AI_INVALID_CONFIGURATION);
    }

    @Override
    public String toString() {
        return "ClovaSpeechProperties[apiKey=REDACTED]";
    }
}
