package com.knot.backend.document.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.knot.backend.document.domain.DocumentException;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.test.util.ReflectionTestUtils;

class DocumentTopicRestorationTest {
    private static final Instant NOW = Instant.parse("2026-10-24T00:00:00Z");

    @Test
    @DisplayName("LEGACY 복원은 과거 규칙 버전까지 복원하고 문서 내용과 보관 상태를 보존한다")
    void restoreTopicAssignment_success_legacy() {
        // given
        Document document = document();
        document.reassignCategory(
                category(
                        false,
                        12
                ),
                "reorganization-v1"
        );
        document.archive(NOW.plusSeconds(1));
        DocumentTopicAssignment original = new DocumentTopicAssignment(
                DocumentGroupingScheme.LEGACY,
                null,
                null,
                "old"
        );
        // when
        boolean changed = document.restoreTopicAssignment(
                original,
                null
        );
        // then
        assertThat(changed).isTrue();
        assertThat(document.topicAssignment()).isEqualTo(original);
        assertThat(document.getTitle()).isEqualTo("제목");
        assertThat(document.getContent()).isEqualTo("본문");
        assertThat(document.getTopic()).isEqualTo("안건");
        assertThat(document.getStatus()).isEqualTo(DocumentStatus.ARCHIVED);
        assertThat(document.getArchivedAt()).isEqualTo(NOW.plusSeconds(1));
    }

    @ParameterizedTest
    @EnumSource(DocumentCategoryAssignmentSource.class)
    @DisplayName("큰 주제 복원은 원래 source와 ruleVersion을 정확히 복원한다")
    void restoreTopicAssignment_success_broad(DocumentCategoryAssignmentSource source) {
        // given
        Document document = document();
        DocumentCategory category = category(
                source == DocumentCategoryAssignmentSource.FALLBACK,
                12
        );
        DocumentTopicAssignment original = new DocumentTopicAssignment(
                DocumentGroupingScheme.BROAD,
                31L,
                source,
                "v0"
        );
        // when
        document.restoreTopicAssignment(
                original,
                category
        );
        // then
        assertThat(document.topicAssignment()).isEqualTo(original);
        assertThat(
                document.restoreTopicAssignment(
                        original,
                        category
                )
        ).isFalse();
    }

    @Test
    @DisplayName("다른 Workspace 주제로 복원하지 않는다")
    void restoreTopicAssignment_failure_foreignCategory() {
        // given
        Document document = document();
        DocumentTopicAssignment assignment = new DocumentTopicAssignment(
                DocumentGroupingScheme.BROAD,
                31L,
                DocumentCategoryAssignmentSource.AI_NEW,
                "v0"
        );
        // when & then
        assertThatThrownBy(
                () -> document.restoreTopicAssignment(
                        assignment,
                        category(
                                false,
                                13
                        )
                )
        ).isInstanceOf(DocumentException.class);
        assertThat(document.getGroupingScheme()).isEqualTo(DocumentGroupingScheme.LEGACY);
    }

    @Test
    @DisplayName("불완전한 큰 주제 배정과 LEGACY의 주제 참조는 거절한다")
    void assignment_failure_invalidTuple() {
        // when & then
        assertThatThrownBy(
                () -> new DocumentTopicAssignment(
                        DocumentGroupingScheme.BROAD,
                        null,
                        null,
                        null
                )
        ).isInstanceOf(DocumentException.class);
        assertThatThrownBy(
                () -> new DocumentTopicAssignment(
                        DocumentGroupingScheme.LEGACY,
                        31L,
                        null,
                        null
                )
        ).isInstanceOf(DocumentException.class);
    }

    @Test
    @DisplayName("복원 작업은 대상 버전과 원본 작업을 기록하고 공통 취소·적용 상태를 사용한다")
    void restoration_success_retainsVersion() {
        // given
        DocumentTopicOperation operation = DocumentTopicOperation.restoration(
                12,
                1,
                UUID.randomUUID(),
                91,
                2,
                NOW
        );
        operation.capture(1);
        UUID token = operation.claim(NOW);
        operation.checkpoint(
                token,
                1,
                true,
                NOW
        );
        UUID applyToken = operation.claim(NOW);
        // when
        operation.beginApplying(
                applyToken,
                NOW
        );
        operation.succeed(
                applyToken,
                1,
                NOW
        );
        // then
        assertThat(operation.getTargetVersionId()).isEqualTo(91);
        assertThat(operation.getRestorationSourceOperationId()).isEqualTo(2);
        assertThat(operation.getKind()).isEqualTo(DocumentTopicOperationKind.RESTORE);
        assertThat(operation.getStatus()).isEqualTo(DocumentTopicOperationStatus.SUCCEEDED);
        assertThat(operation.getDeadlineAt()).isEqualTo(NOW.plusSeconds(1800));
    }

    @Test
    @DisplayName("복원 버전이나 원본 식별자가 양수가 아니면 접수하지 않는다")
    void restoration_failure_invalidVersion() {
        // when & then
        assertThatThrownBy(
                () -> DocumentTopicOperation.restoration(
                        12,
                        1,
                        UUID.randomUUID(),
                        0,
                        2,
                        NOW
                )
        ).isInstanceOf(DocumentException.class);
    }

    private Document document() {
        Document document = Document.createDraft(
                12,
                1,
                1,
                1,
                "안건",
                "제목",
                "요약",
                "본문",
                NOW
        );
        ReflectionTestUtils.setField(
                document,
                "id",
                1L
        );
        return document;
    }

    private DocumentCategory category(
            boolean fallback,
            long workspaceId
    ) {
        DocumentCategory category = DocumentCategory.create(
                workspaceId,
                "녹음",
                "녹음 관련",
                NOW
        );
        if (fallback) {
            category = DocumentCategory.createFallback(
                    workspaceId,
                    NOW
            );
        }
        ReflectionTestUtils.setField(
                category,
                "id",
                31L
        );
        return category;
    }
}
