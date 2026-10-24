package com.knot.backend.document.application.dto.result;
public record DocumentTopicTarget(
        long documentId,
        String agendaTopic,
        String title,
        String summary,
        String evidence,
        Long beforeCategoryId,
        String beforeGroupingScheme,
        String beforeAssignmentSource,
        String beforeRuleVersion,
        String afterKey
) {
}
