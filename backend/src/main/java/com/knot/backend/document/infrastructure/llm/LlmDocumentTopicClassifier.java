package com.knot.backend.document.infrastructure.llm;

import com.knot.backend.document.domain.model.DocumentTopic;
import com.knot.backend.document.application.DocumentTopicClassifier;
import com.knot.backend.document.domain.DocumentErrorCode;
import com.knot.backend.document.domain.DocumentException;
import com.knot.backend.global.infrastructure.llm.LlmClient;
import com.knot.backend.global.infrastructure.llm.LlmCompletionRequest;
import com.knot.backend.global.exception.LlmErrorCode;
import com.knot.backend.global.exception.LlmException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
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
public class LlmDocumentTopicClassifier implements DocumentTopicClassifier {

    private final DocumentTopicPrompt prompt;
    private final LlmClient client;
    private final ObjectMapper mapper;

    public LlmDocumentTopicClassifier(
            DocumentTopicPrompt prompt,
            LlmClient client,
            ObjectMapper mapper
    ) {
        this.prompt = prompt;
        this.client = client;
        this.mapper = mapper.rebuild()
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .build();
    }

    @Override
    public List<DocumentTopic> classify(String transcriptContent) {
        LlmCompletionRequest request = prompt.createRequest(transcriptContent);
        try {
            return parseTopics(client.complete(request));
        } catch (LlmException exception) {
            if (exception.getErrorCode() != LlmErrorCode.LLM_INPUT_LIMIT_EXCEEDED) {
                throw exception;
            }
            return new LongTranscriptTopicClassifier(
                    client,
                    prompt,
                    mapper,
                    this::parseTopics
            ).classify(transcriptContent);
        }
    }

    private List<DocumentTopic> parseTopics(String response) {
        JsonNode root = readResponse(response);
        validateTopicsArray(root);
        List<DocumentTopic> topics = new ArrayList<>();
        for (JsonNode topic : root.path("topics")) {
            validateTopicName(topic);
            String topicName = topic.asString();
            topics.add(createTopic(topicName));
        }
        return removeDuplicateTopics(topics);
    }

    private JsonNode readResponse(String response) {
        try (JsonParser parser = mapper.createParser(response)) {
            JsonNode root = mapper.readTree(parser);
            if (parser.nextToken() != null) {
                throw invalidResponse();
            }
            return root;
        } catch (JacksonException exception) {
            throw invalidResponse(exception);
        }
    }

    private void validateTopicsArray(JsonNode root) {
        validateResponseObject(root);
        if (root.size() != 1) {
            throw invalidResponse();
        }
        JsonNode topics = root.path("topics");
        if (!topics.isArray()) {
            throw invalidResponse();
        }
    }

    private void validateResponseObject(JsonNode root) {
        if (root == null) {
            throw invalidResponse();
        }
        if (!root.isObject()) {
            throw invalidResponse();
        }
    }

    private void validateTopicName(JsonNode topic) {
        if (!topic.isString()) {
            throw invalidResponse();
        }
    }

    private DocumentTopic createTopic(String topic) {
        try {
            return DocumentTopic.of(topic);
        } catch (DocumentException exception) {
            throw invalidResponse(exception);
        }
    }

    private List<DocumentTopic> removeDuplicateTopics(List<DocumentTopic> topics) {
        return List.copyOf(new LinkedHashSet<>(topics));
    }

    private DocumentException invalidResponse() {
        return new DocumentException(DocumentErrorCode.INVALID_TOPIC_CLASSIFICATION_RESPONSE);
    }

    private DocumentException invalidResponse(Throwable cause) {
        return new DocumentException(
                DocumentErrorCode.INVALID_TOPIC_CLASSIFICATION_RESPONSE,
                cause
        );
    }
}
