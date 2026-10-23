package com.knot.backend.document.domain.model;

import com.knot.backend.document.domain.DocumentErrorCode;
import com.knot.backend.document.domain.DocumentException;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import lombok.Getter;

@Getter
@Embeddable
public class DocumentCategoryDecision {

    @Column(name = "existing_category_id")
    private Long existingCategoryId;

    @Column(name = "new_category_name", length = 80)
    private String newName;

    @Column(name = "new_category_description", length = 500)
    private String newDescription;

    @Enumerated(EnumType.STRING)
    @Column(name = "assignment_source", length = 20)
    private DocumentCategoryAssignmentSource source;

    protected DocumentCategoryDecision() {}

    private DocumentCategoryDecision(
            Long existingCategoryId,
            String name,
            String description,
            DocumentCategoryAssignmentSource source
    ) {
        this.existingCategoryId = existingCategoryId;
        this.newName = name;
        this.newDescription = description;
        this.source = source;
    }

    public static DocumentCategoryDecision reuse(
            long id,
            DocumentCategoryAssignmentSource source
    ) {
        if (id <= 0 || !isReuseSource(source)) {
            throw new DocumentException(DocumentErrorCode.INVALID_DOCUMENT_DATA);
        }
        return new DocumentCategoryDecision(
                id,
                null,
                null,
                source
        );
    }

    public static DocumentCategoryDecision create(
            String name,
            String description,
            DocumentCategoryAssignmentSource source
    ) {
        if (source != DocumentCategoryAssignmentSource.AI_NEW && source != DocumentCategoryAssignmentSource.RULE_NEW) {
            throw new DocumentException(DocumentErrorCode.INVALID_DOCUMENT_DATA);
        }
        return new DocumentCategoryDecision(
                null,
                DocumentCategory.normalizeName(name),
                DocumentCategory.normalizeDescription(description),
                source
        );
    }

    public static DocumentCategoryDecision fallback() {
        return new DocumentCategoryDecision(
                null,
                null,
                null,
                DocumentCategoryAssignmentSource.FALLBACK
        );
    }

    public boolean isNew() {
        return source == DocumentCategoryAssignmentSource.AI_NEW || source == DocumentCategoryAssignmentSource.RULE_NEW;
    }

    public boolean isFallback() {
        return source == DocumentCategoryAssignmentSource.FALLBACK;
    }

    private static boolean isReuseSource(DocumentCategoryAssignmentSource source) {
        return source == DocumentCategoryAssignmentSource.AI_REUSE
                || source == DocumentCategoryAssignmentSource.RULE_REUSE;
    }
}
