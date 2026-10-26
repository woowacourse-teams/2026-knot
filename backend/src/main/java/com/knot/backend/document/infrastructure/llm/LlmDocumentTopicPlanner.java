package com.knot.backend.document.infrastructure.llm;

import com.knot.backend.document.application.dto.result.DocumentTopicCandidate;
import com.knot.backend.document.application.dto.result.DocumentTopicTarget;
import com.knot.backend.document.application.dto.result.DocumentTopicExecution;
import com.knot.backend.document.domain.DocumentException;
import com.knot.backend.document.domain.DocumentErrorCode;
import com.knot.backend.document.domain.model.DocumentTopicPlan;
import com.knot.backend.global.config.DocumentTopicReorganizationProperties;
import com.knot.backend.global.exception.LlmErrorCode;
import com.knot.backend.global.exception.LlmException;
import com.knot.backend.global.infrastructure.llm.LlmGenerationOptions;
import com.knot.backend.global.infrastructure.llm.LlmCompletionRequest;
import com.knot.backend.global.infrastructure.llm.LlmClient;
import com.knot.backend.global.infrastructure.llm.LlmMessage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Clock;
import java.util.Map;
import java.util.List;
import java.util.Set;
import java.util.LinkedHashMap;
import java.util.stream.Collectors;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.databind.node.ArrayNode;

@Component
@ConditionalOnProperty(prefix = "knot.document-topic-reorganization", name = "enabled", havingValue = "true")
public class LlmDocumentTopicPlanner {
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(150);
    private static final int BATCH_OUTPUT_TOKENS = 4096;
    private static final int CONSOLIDATION_OUTPUT_TOKENS = 8192;

    private final LlmClient client;
    private final ObjectMapper mapper;
    private final DocumentTopicReorganizationProperties properties;
    private final String system;
    private final Clock clock;
    private final DocumentTopicOutputSchema schema;

    public LlmDocumentTopicPlanner(
            LlmClient client,
            ObjectMapper mapper,
            DocumentTopicReorganizationProperties properties,
            Clock clock
    ) {
        this.client = client;
        this.mapper = mapper.rebuild()
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .build();
        this.properties = properties;
        this.clock = clock;
        system = readResource("reorganization-system.txt");
        schema = new DocumentTopicOutputSchema(mapper);
    }

    public DocumentTopicPlan plan(DocumentTopicExecution execution) {
        String input = serialize(execution);
        if (input.length() > properties.getMaxInputCharacters()) {
            throw new LlmException(LlmErrorCode.LLM_INPUT_LIMIT_EXCEEDED);
        }
        Duration remaining = Duration.between(
                clock.instant(),
                execution.deadline()
        );
        Duration timeout = REQUEST_TIMEOUT;
        if (remaining.compareTo(timeout) < 0) {
            timeout = remaining;
        }
        if (timeout.isNegative() || timeout.isZero()) {
            throw new LlmException(LlmErrorCode.LLM_TIMEOUT);
        }
        String response = client.complete(
                new LlmCompletionRequest(
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
                        "document_topic_reorganization",
                        schema.create(execution),
                        new LlmGenerationOptions(
                                0.1,
                                0.9,
                                20,
                                1.0,
                                false,
                                null,
                                outputTokens(execution)
                        ),
                        timeout
                )
        );
        return parse(
                response,
                execution
        );
    }

    private String serialize(DocumentTopicExecution execution) {
        ObjectNode root = mapper.createObjectNode();
        String mode = "BATCH";
        if (execution.consolidate()) {
            mode = "CONSOLIDATE";
        }
        root.put(
                "mode",
                mode
        );
        ArrayNode categories = root.putArray("categories");
        for (DocumentTopicCandidate category : execution.categories()) {
            ObjectNode node = categories.addObject();
            node.put(
                    "key",
                    category.key()
            );
            node.put(
                    "name",
                    category.name()
            );
            node.put(
                    "description",
                    category.description()
            );
            node.put(
                    "existing",
                    category.existingCategoryId() != null
            );
        }
        ArrayNode documents = root.putArray("documents");
        for (DocumentTopicTarget document : execution.targets()) {
            ObjectNode node = documents.addObject();
            node.put(
                    "documentId",
                    document.documentId()
            );
            node.put(
                    "agendaTopic",
                    document.agendaTopic()
            );
            node.put(
                    "title",
                    document.title()
            );
            node.put(
                    "summary",
                    document.summary()
            );
            node.put(
                    "evidence",
                    document.evidence()
            );
        }
        return mapper.writeValueAsString(root);
    }

    private DocumentTopicPlan parse(
            String response,
            DocumentTopicExecution execution
    ) {
        JsonNode root = read(response);
        requireObject(
                root,
                Set.of(
                        "assignments",
                        "newCategories",
                        "categoryMapping"
                )
        );
        Map<Long, String> assignments = new LinkedHashMap<>();
        JsonNode assignmentObject = object(
                root,
                "assignments"
        );
        for (Map.Entry<String, JsonNode> entry : assignmentObject.properties()) {
            assignments.put(
                    documentId(entry.getKey()),
                    textValue(entry.getValue())
            );
        }
        Map<String, String> names = new LinkedHashMap<>();
        Map<String, String> descriptions = new LinkedHashMap<>();
        for (JsonNode node : array(
                root,
                "newCategories"
        )) {
            requireObject(
                    node,
                    Set.of(
                            "key",
                            "name",
                            "description"
                    )
            );
            String key = text(
                    node,
                    "key"
            );
            if (!validNewKey(
                    key,
                    execution.targets()
                            .size()
            ) || names.put(key,
                    text(
                            node,
                            "name"
                    )
            ) != null) {
                throw invalid();
            }
            descriptions.put(
                    key,
                    text(
                            node,
                            "description"
                    )
            );
        }
        Map<String, String> mapping = new LinkedHashMap<>();
        JsonNode mappingObject = object(
                root,
                "categoryMapping"
        );
        for (Map.Entry<String, JsonNode> entry : mappingObject.properties()) {
            mapping.put(
                    entry.getKey(),
                    textValue(entry.getValue())
            );
        }
        DocumentTopicPlan plan = new DocumentTopicPlan(
                assignments,
                mapping,
                names,
                descriptions
        );
        validateMode(
                plan,
                execution
        );
        return plan;
    }

    private void validateMode(
            DocumentTopicPlan plan,
            DocumentTopicExecution execution
    ) {
        Set<String> keys = execution.categories()
                .stream()
                .map(DocumentTopicCandidate::key)
                .collect(Collectors.toSet());
        if (execution.consolidate()) {
            validateConsolidationArrays(plan);
            plan.validateMapping(
                    keys,
                    keys
            );
            return;
        }
        if (!plan.getCategoryMapping()
                .isEmpty()) {
            throw invalid();
        }
        Set<Long> ids = execution.targets()
                .stream()
                .map(DocumentTopicTarget::documentId)
                .collect(Collectors.toSet());
        plan.validateAssignments(
                ids,
                keys
        );
    }

    private void validateConsolidationArrays(DocumentTopicPlan plan) {
        if (!plan.getAssignments()
                .isEmpty()) {
            throw invalid();
        }
        if (!plan.getNewNames()
                .isEmpty()) {
            throw invalid();
        }
    }

    private JsonNode read(String response) {
        if (response == null || response.length() > 100000) {
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

    private int outputTokens(DocumentTopicExecution execution) {
        if (execution.consolidate()) {
            return CONSOLIDATION_OUTPUT_TOKENS;
        }
        return BATCH_OUTPUT_TOKENS;
    }

    private long documentId(String key) {
        try {
            long id = Long.parseLong(key);
            if (id <= 0 || !Long.toString(id)
                    .equals(key)) {
                throw invalid();
            }
            return id;
        } catch (NumberFormatException exception) {
            throw invalid();
        }
    }

    private boolean validNewKey(
            String key,
            int count
    ) {
        if (!key.matches("n:[1-9][0-9]{0,2}")) {
            return false;
        }
        return Integer.parseInt(key.substring(2)) <= count;
    }

    private JsonNode object(
            JsonNode root,
            String name
    ) {
        JsonNode value = root.path(name);
        if (!value.isObject()) {
            throw invalid();
        }
        return value;
    }

    private JsonNode array(
            JsonNode root,
            String name
    ) {
        JsonNode value = root.path(name);
        if (!value.isArray()) {
            throw invalid();
        }
        return value;
    }

    private String text(
            JsonNode node,
            String name
    ) {
        return textValue(node.path(name));
    }

    private String textValue(JsonNode value) {
        if (!value.isString() || value.asString()
                .length() > 500) {
            throw invalid();
        }
        return value.asString();
    }

    private void requireObject(
            JsonNode node,
            Set<String> fields
    ) {
        if (node == null || !node.isObject() || node.size() != fields.size()) {
            throw invalid();
        }
        for (String field : fields) {
            if (!node.has(field)) {
                throw invalid();
            }
        }
    }

    private String readResource(String name) {
        try (InputStream stream = new ClassPathResource("llm/document/" + name).getInputStream()) {
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

    private DocumentException invalid() {
        return new DocumentException(DocumentErrorCode.INVALID_TOPIC_PLAN);
    }
}
