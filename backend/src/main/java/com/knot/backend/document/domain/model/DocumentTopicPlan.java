package com.knot.backend.document.domain.model;

import com.knot.backend.document.domain.DocumentErrorCode;
import com.knot.backend.document.domain.DocumentException;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.LinkedHashMap;
import lombok.Getter;

@Getter
public class DocumentTopicPlan {
    private static final int MAX_CANDIDATE_KEY_LENGTH = 80;

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

    public DocumentTopicPlan scopeNewKeys(long firstDocumentId) {
        if (firstDocumentId <= 0) {
            throw invalid();
        }
        Map<String, String> renamed = new LinkedHashMap<>();
        for (String key : newNames.keySet()) {
            renamed.put(
                    key,
                    scopedKey(
                            firstDocumentId,
                            key
                    )
            );
        }
        Map<Long, String> scopedAssignments = new LinkedHashMap<>();
        assignments.forEach(
                (
                        id,
                        key
                ) -> scopedAssignments.put(
                        id,
                        renamed.getOrDefault(
                                key,
                                key
                        )
                )
        );
        Map<String, String> names = new LinkedHashMap<>();
        Map<String, String> descriptions = new LinkedHashMap<>();
        renamed.forEach(
                (
                        key,
                        scoped
                ) -> names.put(
                        scoped,
                        newNames.get(key)
                )
        );
        renamed.forEach(
                (
                        key,
                        scoped
                ) -> descriptions.put(
                        scoped,
                        newDescriptions.get(key)
                )
        );
        return new DocumentTopicPlan(
                scopedAssignments,
                categoryMapping,
                names,
                descriptions
        );
    }

    private String scopedKey(
            long firstDocumentId,
            String key
    ) {
        if (!key.startsWith("n:") || key.length() <= 2) {
            throw invalid();
        }
        String scoped = "n:b" + firstDocumentId + "-" + key.substring(2);
        if (scoped.length() > MAX_CANDIDATE_KEY_LENGTH) {
            throw invalid();
        }
        return scoped;
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
