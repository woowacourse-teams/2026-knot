package com.knot.backend.document.infrastructure.llm;

import com.knot.backend.document.application.DocumentCategoryAssigner;
import com.knot.backend.document.application.dto.request.DocumentCategoryInput;
import com.knot.backend.document.domain.DocumentErrorCode;
import com.knot.backend.document.domain.DocumentException;
import com.knot.backend.document.domain.model.DocumentCategory;
import com.knot.backend.document.domain.model.DocumentCategoryAssignmentSource;
import com.knot.backend.document.domain.model.DocumentCategoryDecision;
import com.knot.backend.global.infrastructure.llm.LlmClient;
import java.time.Duration;
import java.util.Set;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonParser;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
@ConditionalOnProperty(prefix = "knot.llm", name = "enabled", havingValue = "true")
public class LlmDocumentCategoryAssigner implements DocumentCategoryAssigner {
    private static final Set<String> FIELDS = Set.of(
            "existingCategoryId",
            "newName",
            "newDescription"
    );

    private final LlmClient client;
    private final DocumentCategoryPrompt prompt;
    private final ObjectMapper mapper;

    public LlmDocumentCategoryAssigner(
            LlmClient client,
            DocumentCategoryPrompt prompt,
            ObjectMapper mapper
    ) {
        this.client = client;
        this.prompt = prompt;
        this.mapper = mapper.rebuild()
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .build();
    }

    @Override
    public DocumentCategoryDecision assign(
            DocumentCategoryInput input,
            Duration timeout
    ) {
        String response = client.complete(
                prompt.createRequest(
                        input,
                        timeout
                )
        );
        JsonNode root = read(response);
        validateFields(root);
        JsonNode existing = root.path("existingCategoryId");
        if (!existing.isNull()) {
            return readExisting(
                    existing,
                    root,
                    input
            );
        }
        return readNew(
                root,
                input
        );
    }

    private DocumentCategoryDecision readExisting(
            JsonNode id,
            JsonNode root,
            DocumentCategoryInput input
    ) {
        if (!id.isIntegralNumber() || !id.canConvertToLong() || id.asLong() <= 0) {
            throw invalid();
        }
        if (!root.path("newName")
                .isNull()
                || !root.path("newDescription")
                        .isNull()) {
            throw invalid();
        }
        long value = id.asLong();
        boolean known = input.categories()
                .stream()
                .filter(category -> !category.isFallback())
                .map(DocumentCategory::getId)
                .anyMatch(candidate -> candidate == value);
        if (!known) {
            throw invalid();
        }
        return DocumentCategoryDecision.reuse(
                value,
                DocumentCategoryAssignmentSource.AI_REUSE
        );
    }

    private DocumentCategoryDecision readNew(
            JsonNode root,
            DocumentCategoryInput input
    ) {
        JsonNode name = root.path("newName");
        JsonNode description = root.path("newDescription");
        if (!name.isString() || !description.isString()) {
            throw invalid();
        }
        try {
            DocumentCategoryDecision decision = DocumentCategoryDecision.create(
                    name.asString(),
                    description.asString(),
                    DocumentCategoryAssignmentSource.AI_NEW
            );
            validateBroadName(
                    decision,
                    input
            );
            return decision;
        } catch (DocumentException exception) {
            throw invalid();
        }
    }

    private void validateBroadName(
            DocumentCategoryDecision decision,
            DocumentCategoryInput input
    ) {
        var document = input.document();
        String title = DocumentCategory.normalizeDescription(document.title());
        if (title.equals(decision.getNewName())) {
            throw invalid();
        }
    }

    private JsonNode read(String response) {
        if (response == null) {
            throw invalid();
        }
        try (JsonParser parser = mapper.createParser(response)) {
            JsonNode root = mapper.readTree(parser);
            if (parser.nextToken() != null) {
                throw invalid();
            }
            return root;
        } catch (JacksonException exception) {
            throw invalid();
        }
    }

    private void validateFields(JsonNode root) {
        if (root == null || !root.isObject() || root.size() != FIELDS.size()) {
            throw invalid();
        }
        for (String field : FIELDS) {
            if (!root.has(field)) {
                throw invalid();
            }
        }
    }

    private DocumentException invalid() {
        return new DocumentException(DocumentErrorCode.INVALID_DOCUMENT_CATEGORY_RESPONSE);
    }
}
