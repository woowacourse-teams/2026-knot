package com.knot.backend.recording.infrastructure.llm;

import com.knot.backend.global.exception.LlmErrorCode;
import com.knot.backend.global.exception.LlmException;
import com.knot.backend.global.infrastructure.llm.LlmCompletionRequest;
import com.knot.backend.global.infrastructure.llm.LlmGenerationOptions;
import com.knot.backend.global.infrastructure.llm.LlmMessage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

public class MeetingSummaryPrompt {
    private static final Duration TIMEOUT = Duration.ofSeconds(150);
    private static final int OUTPUT_TOKENS = 4096;

    private final ObjectMapper mapper;
    private final String system;
    private final JsonNode schema;

    public MeetingSummaryPrompt(ObjectMapper mapper) {
        this.mapper = mapper;
        system = read("meeting-summary-system.txt");
        schema = mapper.readTree(read("meeting-summary-schema.json"));
    }

    public LlmCompletionRequest createRequest(String transcriptText) {
        if (transcriptText == null || transcriptText.isBlank()) {
            throw new LlmException(LlmErrorCode.LLM_INVALID_REQUEST);
        }
        String input = mapper.writeValueAsString(
                mapper.createObjectNode()
                        .put(
                                "transcriptText",
                                transcriptText
                        )
        );
        return new LlmCompletionRequest(
                List.of(
                        new LlmMessage(
                                "system",
                                system
                        ),
                        new LlmMessage(
                                "user",
                                input
                        )
                ),
                "meeting_summary",
                schema,
                new LlmGenerationOptions(
                        0.2,
                        0.9,
                        20,
                        1.0,
                        false,
                        null,
                        OUTPUT_TOKENS
                ),
                TIMEOUT
        );
    }

    private String read(String file) {
        try (InputStream stream = new ClassPathResource("llm/recording/" + file).getInputStream()) {
            return new String(
                    stream.readAllBytes(),
                    StandardCharsets.UTF_8
            );
        } catch (IOException exception) {
            throw new LlmException(
                    LlmErrorCode.LLM_INVALID_CONFIGURATION,
                    exception
            );
        }
    }
}
