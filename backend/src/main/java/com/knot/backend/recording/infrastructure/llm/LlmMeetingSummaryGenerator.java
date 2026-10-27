package com.knot.backend.recording.infrastructure.llm;

import com.knot.backend.global.exception.LlmErrorCode;
import com.knot.backend.global.exception.LlmException;
import com.knot.backend.global.infrastructure.llm.LlmClient;
import com.knot.backend.recording.application.MeetingSummaryGenerator;
import com.knot.backend.recording.domain.RecordingErrorCode;
import com.knot.backend.recording.domain.RecordingException;
import com.knot.backend.recording.domain.model.MeetingSummaryContent;
import java.util.ArrayList;
import java.util.List;
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
    private static final int MAX_CALLS = 32;
    private static final int MAX_DEPTH = 8;

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
        Budget budget = new Budget();
        try {
            return finalSummary(
                    transcriptText,
                    budget
            );
        } catch (LlmException exception) {
            if (exception.getErrorCode() != LlmErrorCode.LLM_INPUT_LIMIT_EXCEEDED) {
                throw exception;
            }
            List<String> parts = new ArrayList<>();
            extractParts(
                    readTranscriptText(transcriptText),
                    parts,
                    budget,
                    0
            );
            return merge(
                    parts,
                    budget,
                    0
            );
        }
    }

    private String readTranscriptText(String input) {
        if (input == null || !input.stripLeading()
                .startsWith("{")) {
            return input;
        }
        JsonNode root;
        try {
            root = mapper.readTree(input);
        } catch (JacksonException exception) {
            return input;
        }
        if (root == null || !root.isObject() || !root.has("transcription")) {
            return input;
        }
        JsonNode segments = root.path("transcription");
        if (!segments.isArray() || segments.isEmpty()) {
            throw new LlmException(LlmErrorCode.LLM_INVALID_REQUEST);
        }
        List<String> utterances = new ArrayList<>();
        for (JsonNode segment : segments) {
            JsonNode text = segment.path("text");
            if (!text.isString()) {
                throw new LlmException(LlmErrorCode.LLM_INVALID_REQUEST);
            }
            utterances.add(text.asString());
        }
        return String.join(
                "\n",
                utterances
        );
    }

    private String finalSummary(
            String transcriptText,
            Budget budget
    ) {
        budget.consume();
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

    private void extractParts(
            String input,
            List<String> output,
            Budget budget,
            int depth
    ) {
        if (depth >= MAX_DEPTH) {
            throw new LlmException(LlmErrorCode.LLM_INPUT_LIMIT_EXCEEDED);
        }
        for (String part : split(input)) {
            try {
                budget.consume();
                JsonNode root = read(client.complete(prompt.createIntermediateRequest(part)));
                if (root.size() != 1 || !root.path("content")
                        .isString() || root.path("content")
                                .asString()
                                .isBlank()) {
                    throw new LlmException(LlmErrorCode.LLM_INVALID_RESPONSE);
                }
                output.add(
                        root.path("content")
                                .asString()
                );
            } catch (LlmException exception) {
                if (exception.getErrorCode() != LlmErrorCode.LLM_INPUT_LIMIT_EXCEEDED) {
                    throw exception;
                }
                extractParts(
                        part,
                        output,
                        budget,
                        depth + 1
                );
            }
        }
    }

    private String merge(
            List<String> parts,
            Budget budget,
            int depth
    ) {
        String combined = String.join(
                "\n\n",
                parts
        );
        try {
            return finalSummary(
                    combined,
                    budget
            );
        } catch (LlmException exception) {
            if (exception.getErrorCode() != LlmErrorCode.LLM_INPUT_LIMIT_EXCEEDED || depth >= MAX_DEPTH) {
                throw exception;
            }
            List<String> reduced = new ArrayList<>();
            extractParts(
                    combined,
                    reduced,
                    budget,
                    depth + 1
            );
            if (String.join(
                    "\n\n",
                    reduced
            )
                    .length() >= combined.length()) {
                throw exception;
            }
            return merge(
                    reduced,
                    budget,
                    depth + 1
            );
        }
    }

    private List<String> split(String input) {
        if (input.codePointCount(
                0,
                input.length()
        ) < 2) {
            throw new LlmException(LlmErrorCode.LLM_INPUT_LIMIT_EXCEEDED);
        }
        int middle = input.offsetByCodePoints(
                0,
                input.codePointCount(
                        0,
                        input.length()
                ) / 2
        );
        int boundary = input.lastIndexOf(
                '\n',
                middle
        );
        if (boundary > input.length() / 4) {
            middle = boundary + 1;
        }
        return List.of(
                input.substring(
                        0,
                        middle
                ),
                input.substring(middle)
        );
    }

    private static class Budget {
        private int calls;

        void consume() {
            if (Thread.currentThread()
                    .isInterrupted()) {
                throw new LlmException(LlmErrorCode.LLM_CALL_INTERRUPTED);
            }
            if (++calls > MAX_CALLS) {
                throw new LlmException(LlmErrorCode.LLM_INPUT_LIMIT_EXCEEDED);
            }
        }
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
