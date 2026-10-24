package com.knot.backend.document.infrastructure.llm;

import com.knot.backend.document.application.dto.result.DocumentTopicCandidate;
import com.knot.backend.document.application.dto.result.DocumentTopicExecution;
import com.knot.backend.document.application.dto.result.DocumentTopicTarget;
import java.util.List;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

final class DocumentTopicOutputSchema {

    private final ObjectMapper mapper;

    DocumentTopicOutputSchema(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    ObjectNode create(DocumentTopicExecution execution) {
        ObjectNode assignments = object();
        ObjectNode mapping = object();
        ArrayNode allowed = mapper.createArrayNode();
        for (DocumentTopicCandidate candidate : execution.categories()) {
            allowed.add(candidate.key());
        }
        if (execution.consolidate()) {
            addMappings(
                    mapping,
                    execution,
                    allowed
            );
        }
        if (!execution.consolidate()) {
            addAssignments(
                    assignments,
                    execution,
                    allowed
            );
        }
        ObjectNode root = object();
        ObjectNode properties = mapper.createObjectNode();
        properties.set(
                "assignments",
                assignments
        );
        properties.set(
                "newCategories",
                newCategories()
        );
        properties.set(
                "categoryMapping",
                mapping
        );
        root.set(
                "properties",
                properties
        );
        root.set(
                "required",
                strings(
                        List.of(
                                "assignments",
                                "newCategories",
                                "categoryMapping"
                        )
                )
        );
        return root;
    }

    private void addAssignments(
            ObjectNode schema,
            DocumentTopicExecution execution,
            ArrayNode allowed
    ) {
        for (int index = 1; index <= execution.targets()
                .size(); index++) {
            allowed.add("n:" + index);
        }
        ObjectNode properties = mapper.createObjectNode();
        ArrayNode required = mapper.createArrayNode();
        for (DocumentTopicTarget target : execution.targets()) {
            String id = Long.toString(target.documentId());
            properties.set(
                    id,
                    choice(allowed)
            );
            required.add(id);
        }
        schema.set(
                "properties",
                properties
        );
        schema.set(
                "required",
                required
        );
    }

    private void addMappings(
            ObjectNode schema,
            DocumentTopicExecution execution,
            ArrayNode allowed
    ) {
        ObjectNode properties = mapper.createObjectNode();
        ArrayNode required = mapper.createArrayNode();
        for (DocumentTopicCandidate candidate : execution.categories()) {
            properties.set(
                    candidate.key(),
                    choice(allowed)
            );
            required.add(candidate.key());
        }
        schema.set(
                "properties",
                properties
        );
        schema.set(
                "required",
                required
        );
    }

    private ObjectNode newCategories() {
        ObjectNode item = object();
        ObjectNode properties = mapper.createObjectNode();
        for (String name : List.of(
                "key",
                "name",
                "description"
        )) {
            ObjectNode field = mapper.createObjectNode();
            field.put(
                    "type",
                    "string"
            );
            properties.set(
                    name,
                    field
            );
        }
        item.set(
                "properties",
                properties
        );
        item.set(
                "required",
                strings(
                        List.of(
                                "key",
                                "name",
                                "description"
                        )
                )
        );
        ObjectNode array = mapper.createObjectNode();
        array.put(
                "type",
                "array"
        );
        array.set(
                "items",
                item
        );
        return array;
    }

    private ObjectNode choice(ArrayNode allowed) {
        ObjectNode choice = mapper.createObjectNode();
        choice.put(
                "type",
                "string"
        );
        choice.set(
                "enum",
                allowed.deepCopy()
        );
        return choice;
    }

    private ObjectNode object() {
        ObjectNode object = mapper.createObjectNode();
        object.put(
                "type",
                "object"
        );
        object.put(
                "additionalProperties",
                false
        );
        object.set(
                "properties",
                mapper.createObjectNode()
        );
        object.set(
                "required",
                mapper.createArrayNode()
        );
        return object;
    }

    private ArrayNode strings(List<String> values) {
        ArrayNode array = mapper.createArrayNode();
        values.forEach(array::add);
        return array;
    }
}
