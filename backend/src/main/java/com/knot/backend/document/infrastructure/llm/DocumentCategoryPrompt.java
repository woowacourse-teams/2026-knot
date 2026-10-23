package com.knot.backend.document.infrastructure.llm;

import com.knot.backend.document.application.dto.request.DocumentCategoryInput;
import com.knot.backend.document.application.dto.result.DocumentGenerationResult;
import com.knot.backend.document.domain.model.DocumentCategory;
import com.knot.backend.global.config.DocumentCategoryProperties;
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
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

@Component
@ConditionalOnProperty(prefix = "knot.llm", name = "enabled", havingValue = "true")
public class DocumentCategoryPrompt {

    private final ObjectMapper mapper;
    private final DocumentCategoryProperties properties;
    private final String system;
    private final JsonNode schema;

    public DocumentCategoryPrompt(
            ObjectMapper mapper,
            DocumentCategoryProperties properties
    ) {
        this.mapper = mapper;
        this.properties = properties;
        system = read("category-system.txt");
        schema = mapper.readTree(read("category-schema.json"));
    }

    public LlmCompletionRequest createRequest(
            DocumentCategoryInput input,
            Duration timeout
    ) {
        String serialized = serialize(input);
        if (serialized.length() > properties.getMaxInputCharacters()) {
            throw new LlmException(LlmErrorCode.LLM_INPUT_LIMIT_EXCEEDED);
        }
        return new LlmCompletionRequest(
                List.of(
                        new LlmMessage(
                                "system",
                                system
                        ),
                        new LlmMessage(
                                "user",
                                serialized
                        )
                ),
                "document_category_assignment",
                schema,
                new LlmGenerationOptions(
                        0.2,
                        0.9,
                        20,
                        1.0,
                        false,
                        null,
                        512
                ),
                timeout
        );
    }

    private String serialize(DocumentCategoryInput input) {
        ObjectNode root = mapper.createObjectNode();
        root.put(
                "agendaTopic",
                input.agendaTopic()
        );
        DocumentGenerationResult document = input.document();
        ObjectNode body = root.putObject("document");
        body.put(
                "title",
                document.title()
        );
        body.put(
                "summary",
                document.summary()
        );
        body.put(
                "content",
                document.content()
        );
        ArrayNode candidates = root.putArray("categories");
        for (DocumentCategory category : input.categories()) {
            addCandidate(
                    candidates,
                    category
            );
        }
        return mapper.writeValueAsString(root);
    }

    private void addCandidate(
            ArrayNode candidates,
            DocumentCategory category
    ) {
        if (category.isFallback()) {
            return;
        }
        ObjectNode candidate = candidates.addObject();
        candidate.put(
                "id",
                category.getId()
        );
        candidate.put(
                "name",
                category.getName()
        );
        candidate.put(
                "description",
                category.getDescription()
        );
    }

    private String read(String filename) {
        try (InputStream stream = new ClassPathResource("llm/document/" + filename).getInputStream()) {
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
