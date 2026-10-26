package com.knot.backend.recording.infrastructure.llm;

import com.knot.backend.global.exception.LlmErrorCode;
import com.knot.backend.global.exception.LlmException;
import com.knot.backend.global.infrastructure.llm.LlmClient;
import com.knot.backend.recording.application.MeetingSummaryGenerator;
import com.knot.backend.recording.domain.RecordingErrorCode;
import com.knot.backend.recording.domain.RecordingException;
import com.knot.backend.recording.domain.model.MeetingSummaryContent;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonParser;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
@ConditionalOnProperty(prefix = "knot.llm", name = "enabled", havingValue = "true")
public class LlmMeetingSummaryGenerator implements MeetingSummaryGenerator {

    private final LlmClient client;
    private final ObjectMapper mapper;
    private final MeetingSummaryPrompt prompt;

    public LlmMeetingSummaryGenerator(
            LlmClient client,
            ObjectMapper mapper
    ) {
        this.client = client;
        this.mapper = mapper.rebuild()
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .build();
        prompt = new MeetingSummaryPrompt(this.mapper);
    }

    @Override
    public String generate(String transcriptText) {
        String response = client.complete(prompt.createRequest(transcriptText));
        JsonNode root = read(response);
        if (root.size() != 1 || !root.path("content")
                .isString()) {
            throw new RecordingException(RecordingErrorCode.INVALID_MEETING_SUMMARY);
        }
        return MeetingSummaryContent.normalize(
                root.path("content")
                        .asString()
        );
    }

    private JsonNode read(String response) {
        if (response == null) {
            throw new LlmException(LlmErrorCode.LLM_INVALID_RESPONSE);
        }
        try (JsonParser parser = mapper.createParser(response)) {
            JsonNode root = mapper.readTree(parser);
            if (root == null || !root.isObject() || parser.nextToken() != null) {
                throw new LlmException(LlmErrorCode.LLM_INVALID_RESPONSE);
            }
            return root;
        } catch (JacksonException exception) {
            throw new LlmException(
                    LlmErrorCode.LLM_INVALID_RESPONSE,
                    exception
            );
        }
    }
}
