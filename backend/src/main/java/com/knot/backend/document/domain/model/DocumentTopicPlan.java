package com.knot.backend.document.domain.model;

import com.knot.backend.document.domain.DocumentErrorCode;
import com.knot.backend.document.domain.DocumentException;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import lombok.Getter;

@Getter
public class DocumentTopicPlan {

    private final Map<Long, String> assignments;
    private final Map<String, String> categoryMapping;
    private final Map<String, String> newNames;
    private final Map<String, String> newDescriptions;

    public DocumentTopicPlan(
            Map<Long, String> assignments,
            Map<String, String> categoryMapping,
            Map<String, String> newNames,
            Map<String, String> newDescriptions
    ) {
        this.assignments = Map.copyOf(assignments);
        this.categoryMapping = Map.copyOf(categoryMapping);
        this.newNames = Map.copyOf(newNames);
        this.newDescriptions = Map.copyOf(newDescriptions);
    }

    public void validateAssignments(
            Set<Long> expected,
            Set<String> existingKeys
    ) {
        if (!assignments.keySet()
                .equals(expected)) {
            throw invalid();
        }
        Set<String> allowed = new HashSet<>(existingKeys);
        allowed.addAll(newNames.keySet());
        if (!allowed.containsAll(assignments.values())) {
            throw invalid();
        }
        validateNew(existingKeys);
    }

    public void validateMapping(
            Set<String> expected,
            Set<String> existingKeys
    ) {
        if (!categoryMapping.keySet()
                .equals(expected)) {
            throw invalid();
        }
        if (!existingKeys.containsAll(categoryMapping.values())) {
            throw invalid();
        }
        for (String target : categoryMapping.values()) {
            if (!target.equals(categoryMapping.get(target))) {
                throw invalid();
            }
        }
    }

    private void validateNew(Set<String> existingKeys) {
        if (!assignments.values()
                .containsAll(newNames.keySet())) {
            throw invalid();
        }
        if (!newNames.keySet()
                .equals(newDescriptions.keySet())) {
            throw invalid();
        }
        for (String key : newNames.keySet()) {
            if (existingKeys.contains(key)) {
                throw invalid();
            }
            DocumentCategory.normalizeName(newNames.get(key));
            DocumentCategory.normalizeDescription(newDescriptions.get(key));
        }
    }

    private DocumentException invalid() {
        return new DocumentException(DocumentErrorCode.INVALID_TOPIC_PLAN);
    }
}
