package com.knot.backend.document.domain.model;

import com.knot.backend.document.domain.DocumentErrorCode;
import com.knot.backend.document.domain.DocumentException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.text.Normalizer;
import java.time.Instant;
import java.util.Set;
import lombok.Getter;

@Getter
@Entity
@Table(name = "document_categories")
public class DocumentCategory {
    private static final Set<String> RESERVED_NAMES = Set.of(
            "기타",
            "미분류",
            "일반"
    );
    public static final int MAX_NAME_LENGTH = 80;
    public static final int MAX_DESCRIPTION_LENGTH = 500;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "workspace_id", nullable = false, updatable = false)
    private long workspaceId;

    @Column(nullable = false, updatable = false, length = MAX_NAME_LENGTH)
    private String name;

    @Column(name = "normalized_name", nullable = false, updatable = false, length = MAX_NAME_LENGTH)
    private String normalizedName;

    @Column(nullable = false, updatable = false, length = MAX_DESCRIPTION_LENGTH)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 20)
    private DocumentCategoryKind kind;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected DocumentCategory() {}

    private DocumentCategory(
            long workspaceId,
            String name,
            String description,
            DocumentCategoryKind kind,
            Instant createdAt
    ) {
        if (workspaceId <= 0 || createdAt == null) {
            throw new DocumentException(DocumentErrorCode.INVALID_DOCUMENT_DATA);
        }
        this.workspaceId = workspaceId;
        this.name = name;
        this.normalizedName = name;
        this.description = description;
        this.kind = kind;
        this.createdAt = createdAt;
    }

    public static DocumentCategory create(
            long workspaceId,
            String name,
            String description,
            Instant now
    ) {
        return new DocumentCategory(
                workspaceId,
                normalizeName(name),
                normalizeDescription(description),
                DocumentCategoryKind.NORMAL,
                now
        );
    }

    public static DocumentCategory createFallback(
            long workspaceId,
            Instant now
    ) {
        return new DocumentCategory(
                workspaceId,
                "기타",
                "자동 배정 장애 시 문서 전달을 위한 분류",
                DocumentCategoryKind.FALLBACK,
                now
        );
    }

    public static String normalizeName(String name) {
        String normalized = normalize(name);
        if (normalized.length() > MAX_NAME_LENGTH || RESERVED_NAMES.contains(normalized)) {
            throw new DocumentException(DocumentErrorCode.INVALID_DOCUMENT_DATA);
        }
        return normalized;
    }

    public static String normalizeDescription(String description) {
        String normalized = normalize(description);
        if (normalized.length() > MAX_DESCRIPTION_LENGTH) {
            throw new DocumentException(DocumentErrorCode.INVALID_DOCUMENT_DATA);
        }
        return normalized;
    }

    private static String normalize(String value) {
        if (DocumentText.isBlank(value)) {
            throw new DocumentException(DocumentErrorCode.INVALID_DOCUMENT_DATA);
        }
        String normalized = Normalizer.normalize(
                value,
                Normalizer.Form.NFC
        );
        return DocumentText.normalizeWhitespace(normalized);
    }

    public boolean isFallback() {
        return kind == DocumentCategoryKind.FALLBACK;
    }
}
