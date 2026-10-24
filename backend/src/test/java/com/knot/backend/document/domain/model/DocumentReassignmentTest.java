package com.knot.backend.document.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.knot.backend.document.domain.DocumentException;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class DocumentReassignmentTest {
    private static final Instant NOW = Instant.parse("2026-10-23T00:00:00Z");

    @Test
    void reassign_success_changesOnlyAssignment() {
        Document document = savedDocument();
        document.archive(NOW);
        DocumentCategory category = category(
                1,
                7
        );
        assertThat(
                document.reassignCategory(
                        category,
                        "reorganization-v1"
                )
        ).isTrue();
        assertThat(document.getCategoryId()).isEqualTo(7);
        assertThat(document.getAssignmentSource()).isEqualTo(DocumentCategoryAssignmentSource.REORGANIZATION);
        assertThat(document.getTopic()).isEqualTo("세부 안건");
        assertThat(document.getTitle()).isEqualTo("제목");
        assertThat(document.getSummary()).isEqualTo("요약");
        assertThat(document.getContent()).isEqualTo("본문");
        assertThat(document.getStatus()).isEqualTo(DocumentStatus.ARCHIVED);
        assertThat(document.getArchivedAt()).isEqualTo(NOW);
    }

    @Test
    void reassign_failure_foreignWorkspace() {
        Document document = savedDocument();
        assertThatThrownBy(
                () -> document.reassignCategory(
                        category(
                                2,
                                7
                        ),
                        "v1"
                )
        ).isInstanceOf(DocumentException.class);
        assertThat(document.getCategoryId()).isNull();
    }

    @Test
    void reassign_failure_unsavedCategoryAndDocument() {
        Document document = savedDocument();
        DocumentCategory unsaved = DocumentCategory.create(
                1,
                "문서 생성",
                "문서 정리 범위",
                NOW
        );
        assertThatThrownBy(
                () -> document.reassignCategory(
                        unsaved,
                        "v1"
                )
        ).isInstanceOf(DocumentException.class);
        Document pending = draft();
        assertThatThrownBy(
                () -> pending.reassignCategory(
                        category(
                                1,
                                7
                        ),
                        "v1"
                )
        ).isInstanceOf(DocumentException.class);
    }

    @Test
    void reassign_failure_fallback() {
        DocumentCategory fallback = DocumentCategory.createFallback(
                1,
                NOW
        );
        ReflectionTestUtils.setField(
                fallback,
                "id",
                7L
        );
        Document document = savedDocument();
        assertThatThrownBy(
                () -> document.reassignCategory(
                        fallback,
                        "v1"
                )
        ).isInstanceOf(DocumentException.class);
    }

    @Test
    void reassign_success_sameAssignmentPreservesSource() {
        Document document = draft();
        DocumentCategory category = category(
                1,
                7
        );
        document.assignCategory(
                category,
                DocumentCategoryAssignmentSource.AI_REUSE,
                "original-v1"
        );
        ReflectionTestUtils.setField(
                document,
                "id",
                1L
        );
        assertThat(
                document.reassignCategory(
                        category,
                        "reorganization-v1"
                )
        ).isFalse();
        assertThat(document.getAssignmentSource()).isEqualTo(DocumentCategoryAssignmentSource.AI_REUSE);
        assertThat(document.getAssignmentRuleVersion()).isEqualTo("original-v1");
    }

    private DocumentCategory category(
            long workspaceId,
            long id
    ) {
        DocumentCategory category = DocumentCategory.create(
                workspaceId,
                "문서 생성",
                "문서 정리 범위",
                NOW
        );
        ReflectionTestUtils.setField(
                category,
                "id",
                id
        );
        return category;
    }

    private Document savedDocument() {
        Document document = draft();
        ReflectionTestUtils.setField(
                document,
                "id",
                1L
        );
        return document;
    }

    private Document draft() {
        return Document.createDraft(
                1,
                2,
                3,
                4,
                "세부 안건",
                "제목",
                "요약",
                "본문",
                NOW
        );
    }
}
