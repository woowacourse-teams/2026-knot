package com.knot.backend.document.domain.model;

import com.knot.backend.document.domain.DocumentErrorCode;
import com.knot.backend.document.domain.DocumentException;

public record DocumentTopicAssignment(
        DocumentGroupingScheme groupingScheme,
        Long categoryId,
        DocumentCategoryAssignmentSource source,
        String ruleVersion
) {
    private static final int MAX_RULE_VERSION_LENGTH = 40;

    public DocumentTopicAssignment {
        if (groupingScheme == null) {
            throw invalid();
        }
        if (groupingScheme == DocumentGroupingScheme.LEGACY && (categoryId != null || source != null)) {
            throw invalid();
        }
        if (groupingScheme == DocumentGroupingScheme.BROAD) {
            requireBroad(
                    categoryId,
                    source,
                    ruleVersion
            );
        }
        if (ruleVersion != null && ruleVersion.length() > MAX_RULE_VERSION_LENGTH) {
            throw invalid();
        }
    }

    private static void requireBroad(
            Long categoryId,
            DocumentCategoryAssignmentSource source,
            String ruleVersion
    ) {
        if (categoryId == null || categoryId <= 0 || source == null || ruleVersion == null) {
            throw invalid();
        }
    }

    private static DocumentException invalid() {
        return new DocumentException(DocumentErrorCode.INVALID_TOPIC_RESTORATION_DATA);
    }
}
