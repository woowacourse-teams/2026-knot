package com.knot.backend.recording.infrastructure.llm;

import com.knot.backend.global.exception.LlmErrorCode;
import com.knot.backend.global.exception.LlmException;
import com.knot.backend.global.infrastructure.llm.LlmClient;
import com.knot.backend.global.infrastructure.llm.LlmCompletionRequest;
import com.knot.backend.global.infrastructure.llm.LlmGenerationOptions;
import com.knot.backend.global.infrastructure.llm.LlmMessage;
import com.knot.backend.recording.application.RecordingTitleGenerator;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonParser;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
@ConditionalOnProperty(prefix = "knot.llm", name = "enabled", havingValue = "true")
public class LlmRecordingTitleGenerator implements RecordingTitleGenerator {
    public static final int MAX_INPUT_CHARACTERS = 12_000;
    private static final String SCHEMA_NAME = "recording_title";
    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    private static final LlmGenerationOptions OPTIONS = new LlmGenerationOptions(
            0.3,
            0.9,
            20,
            1.0,
            false,
            null,
            128
    );

    private final LlmClient client;
    private final ObjectMapper mapper;
    private final String system;
    private final JsonNode schema;

    public LlmRecordingTitleGenerator(
            LlmClient client,
            ObjectMapper mapper
    ) {
        this.client = client;
        this.mapper = mapper.rebuild()
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .build();
        this.system = read("title-system.txt");
        this.schema = this.mapper.readTree(read("title-schema.json"));
    }

    @Override
    public String generate(String transcript) {
        String response = client.complete(
                new LlmCompletionRequest(
                        List.of(
                                new LlmMessage(
                                        "system",
                                        system
                                ),
                                new LlmMessage(
                                        "user",
                                        limitInput(transcript)
                                )
                        ),
                        SCHEMA_NAME,
                        schema,
                        OPTIONS,
                        TIMEOUT
                )
        );
        return readTitle(response);
    }

    private String limitInput(String transcript) {
        if (transcript.length() <= MAX_INPUT_CHARACTERS) {
            return transcript;
        }
        int end = MAX_INPUT_CHARACTERS;
        if (Character.isHighSurrogate(transcript.charAt(end - 1))) {
            end--;
        }
        return transcript.substring(
                0,
                end
        );
    }

    private String readTitle(String response) {
        JsonNode root = readObject(response);
        JsonNode title = root.path("title");
        if (root.size() != 1 || !title.isString()) {
            throw invalidResponse();
        }
        return title.asString();
    }

    private JsonNode readObject(String response) {
        if (response == null) {
            throw invalidResponse();
        }
        try (JsonParser parser = mapper.createParser(response)) {
            JsonNode root = mapper.readTree(parser);
            if (parser.nextToken() != null || root == null || !root.isObject()) {
                throw invalidResponse();
            }
            return root;
        } catch (JacksonException exception) {
            throw new LlmException(
                    LlmErrorCode.LLM_INVALID_RESPONSE,
                    exception
            );
        }
    }

    private String read(String filename) {
        try (InputStream stream = new ClassPathResource("llm/recording/" + filename).getInputStream()) {
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

    private LlmException invalidResponse() {
        return new LlmException(LlmErrorCode.LLM_INVALID_RESPONSE);
    }
}
