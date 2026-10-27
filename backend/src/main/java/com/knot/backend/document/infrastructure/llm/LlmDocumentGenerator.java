package com.knot.backend.document.infrastructure.llm;

import com.knot.backend.document.domain.model.DocumentGenerationInput;
import com.knot.backend.document.domain.model.DocumentText;
import com.knot.backend.document.application.DocumentGenerator;
import com.knot.backend.document.application.dto.result.DocumentGenerationResult;
import com.knot.backend.document.domain.DocumentErrorCode;
import com.knot.backend.document.domain.DocumentException;
import com.knot.backend.global.infrastructure.llm.LlmClient;
import com.knot.backend.global.infrastructure.llm.LlmCompletionRequest;
import com.knot.backend.global.exception.LlmErrorCode;
import com.knot.backend.global.exception.LlmException;
import java.util.regex.Pattern;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonParser;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
@ConditionalOnProperty(prefix = "knot.llm", name = "enabled", havingValue = "true")
public class LlmDocumentGenerator implements DocumentGenerator {
    private static final int MAX_EVIDENCE_REDUCTIONS = 3;
    private static final Pattern OUTER_WHITESPACE = Pattern
            .compile("^[\\p{javaWhitespace}\\p{Z}]+|[\\p{javaWhitespace}\\p{Z}]+$");

    private final DocumentGenerationPrompt prompt;
    private final LlmClient client;
    private final ObjectMapper mapper;
    private final DocumentMarkdownValidator validator;

    public LlmDocumentGenerator(
            DocumentGenerationPrompt prompt,
            LlmClient client,
            ObjectMapper mapper,
            DocumentMarkdownValidator validator
    ) {
        this.prompt = prompt;
        this.client = client;
        this.mapper = mapper.rebuild()
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .build();
        this.validator = validator;
    }

    @Override
    public DocumentGenerationResult generate(DocumentGenerationInput input) {
        LlmCompletionRequest request = prompt.createRequest(input);
        String response;
        try {
            response = client.complete(request);
        } catch (LlmException exception) {
            if (exception.getErrorCode() != LlmErrorCode.LLM_INPUT_LIMIT_EXCEEDED) {
                throw exception;
            }
            response = generateFromEvidence(input);
        }
        JsonNode root = readResponse(response);
        validateResponseFields(root);
        String title = stripOuterWhitespace(
                readRequiredText(
                        root,
                        "title"
                )
        );
        String summary = readSummary(root);
        String content = readRequiredText(
                root,
                "content"
        );
        DocumentGenerationResult result = new DocumentGenerationResult(
                title,
                summary,
                content
        );
        validator.validate(result);
        return result;
    }

    private String generateFromEvidence(DocumentGenerationInput input) {
        TopicEvidenceExtractor extractor = new TopicEvidenceExtractor(
                client,
                mapper
        );
        DocumentGenerationInput current = input;
        LlmException lastLimit = null;
        for (int round = 0; round < MAX_EVIDENCE_REDUCTIONS; round++) {
            String evidence = extractor.extract(current);
            if (round > 0 && evidence.length() >= current.getTranscriptContent()
                    .length()) {
                throw lastLimit;
            }
            current = DocumentGenerationInput.from(
                    evidence,
                    input.getTopic()
            );
            try {
                return client.complete(prompt.createRequest(current));
            } catch (LlmException exception) {
                if (exception.getErrorCode() != LlmErrorCode.LLM_INPUT_LIMIT_EXCEEDED) {
                    throw exception;
                }
                lastLimit = exception;
            }
        }
        throw lastLimit;
    }

    private JsonNode readResponse(String response) {
        if (response == null) {
            throw invalidResponse();
        }
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

    private void validateResponseFields(JsonNode root) {
        validateResponseObject(root);
        if (root.size() != 3) {
            throw invalidResponse();
        }
        validateRequiredField(
                root,
                "title"
        );
        validateRequiredField(
                root,
                "summary"
        );
        validateRequiredField(
                root,
                "content"
        );
    }

    private void validateResponseObject(JsonNode root) {
        if (root == null) {
            throw invalidResponse();
        }
        if (!root.isObject()) {
            throw invalidResponse();
        }
    }

    private void validateRequiredField(
            JsonNode root,
            String field
    ) {
        if (!root.has(field)) {
            throw invalidResponse();
        }
    }

    private String readRequiredText(
            JsonNode root,
            String field
    ) {
        JsonNode value = root.path(field);
        if (!value.isString()) {
            throw invalidResponse();
        }
        String text = value.asString();
        if (DocumentText.isBlank(text)) {
            throw invalidResponse();
        }
        return text;
    }

    private String readSummary(JsonNode root) {
        JsonNode summary = root.path("summary");
        if (summary.isNull()) {
            return null;
        }
        if (!summary.isString()) {
            throw invalidResponse();
        }
        String text = summary.asString();
        if (DocumentText.isBlank(text)) {
            return null;
        }
        return text;
    }

    private String stripOuterWhitespace(String value) {
        return OUTER_WHITESPACE.matcher(value)
                .replaceAll("");
    }

    private DocumentException invalidResponse() {
        return new DocumentException(DocumentErrorCode.INVALID_DOCUMENT_GENERATION_RESPONSE);
    }

    private DocumentException invalidResponse(Throwable cause) {
        return new DocumentException(
                DocumentErrorCode.INVALID_DOCUMENT_GENERATION_RESPONSE,
                cause
        );
    }
}
