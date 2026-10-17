package com.knot.backend.document.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.knot.backend.document.DocumentFixtures;
import com.knot.backend.document.application.DocumentConfirmationQuery;
import com.knot.backend.document.application.dto.result.DocumentConfirmationItemResult;
import com.knot.backend.document.application.dto.result.DocumentConfirmationOverviewResult;
import com.knot.backend.document.application.dto.result.DocumentConfirmationSummaryResult;
import com.knot.backend.document.domain.model.DocumentConfirmationCursor;
import com.knot.backend.document.domain.model.DocumentConfirmation;
import com.knot.backend.document.domain.model.DocumentConfirmationState;
import com.knot.backend.testsupport.SharedPostgresDocumentQueryTestSupport;
import com.knot.backend.testsupport.SharedPostgresTestConfiguration;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

@Tag("integration")
@DataJpaTest
@Import({SharedPostgresTestConfiguration.class, DocumentConfirmationQueryAdapter.class,
        DocumentDetailQueryAdapter.class, DocumentGenerationJobListQueryAdapter.class, DocumentListQueryAdapter.class})
class DocumentConfirmationQueryIntegrationTest extends SharedPostgresDocumentQueryTestSupport {
    @Autowired
    private DocumentConfirmationQuery query;
    @Autowired
    private JdbcClient jdbc;
    @Autowired
    private EntityManager entityManager;
    private DocumentFixtures fixtures;
    private long workspaceId;
    private long viewerId;
    private long documentId;

    @BeforeEach
    void setUp() {
        fixtures = new DocumentFixtures(jdbc);
        workspaceId = fixtures.saveWorkspace();
        viewerId = fixtures.saveMember("조회자");
        fixtures.join(
                workspaceId,
                viewerId
        );
        long recordingId = fixtures.saveRecording(
                workspaceId,
                viewerId,
                1000
        );
        long transcriptId = fixtures.saveTranscript(recordingId);
        documentId = fixtures.saveDocument(
                workspaceId,
                recordingId,
                transcriptId,
                fixtures.saveJob(
                        transcriptId,
                        "SUCCEEDED"
                ),
                "정책"
        );
    }

    @Test
    @DisplayName("JPA로 저장한 확인 대상은 전체 집계에 즉시 반영된다")
    void findSummary_success_pendingJpaConfirmation() {
        entityManager.persist(
                DocumentConfirmation.require(
                        documentId,
                        viewerId
                )
        );

        DocumentConfirmationOverviewResult result = summary(viewerId);

        assertThat(
                result.summary()
                        .pendingCount()
        ).isEqualTo(1);
        assertThat(result.confirmedByMe()).isFalse();
    }

    @Test
    @DisplayName("집계 조회 없이 대상 페이지만 읽어도 JPA 저장이 반영된다")
    void findPage_success_pendingJpaConfirmation() {
        entityManager.persist(
                DocumentConfirmation.require(
                        documentId,
                        viewerId
                )
        );

        List<DocumentConfirmationItemResult> targets = query.findPage(
                workspaceId,
                documentId,
                50,
                null
        );

        assertThat(targets).hasSize(1);
        assertThat(
                targets.getFirst()
                        .memberId()
        ).isEqualTo(viewerId);
        assertThat(
                targets.getFirst()
                        .state()
        ).isEqualTo(DocumentConfirmationState.PENDING);
        assertThat(
                targets.getFirst()
                        .confirmedAt()
        ).isNull();
    }

    @Test
    @DisplayName("문서가 있고 대상이 없으면 0명 집계와 빈 페이지를 반환한다")
    void findSummary_success_emptyTargets() {
        // when
        DocumentConfirmationOverviewResult result = summary(viewerId);
        // then
        assertThat(result.documentId()).isEqualTo(documentId);
        assertThat(result.summary()).isEqualTo(
                new DocumentConfirmationSummaryResult(
                        0,
                        0,
                        0
                )
        );
        assertThat(result.confirmedByMe()).isFalse();
        assertThat(
                query.findPage(
                        workspaceId,
                        documentId,
                        50,
                        null
                )
        ).isEmpty();
    }

    @Test
    @DisplayName("전체 집계는 페이지 크기와 무관하며 확인 후 탈퇴한 대상도 확인 인원에 포함한다")
    void findSummary_success_allStatesAndConfirmedViewer() {
        // given
        fixtures.target(
                documentId,
                viewerId,
                DocumentFixtures.CREATED_AT
        );
        long departedConfirmed = target(
                false,
                DocumentFixtures.CREATED_AT
        );
        long pending = target(
                true,
                null
        );
        long excluded = target(
                false,
                null
        );
        // when
        DocumentConfirmationOverviewResult result = summary(viewerId);
        // then
        assertThat(result.confirmedByMe()).isTrue();
        assertThat(result.summary()).isEqualTo(
                new DocumentConfirmationSummaryResult(
                        2,
                        1,
                        1
                )
        );
        assertThat(
                query.findPage(
                        workspaceId,
                        documentId,
                        1,
                        null
                )
        ).hasSize(2);
        assertThat(
                query.findPage(
                        workspaceId,
                        documentId,
                        100,
                        null
                )
        ).extracting(DocumentConfirmationItemResult::memberId)
                .containsExactly(
                        viewerId,
                        departedConfirmed,
                        pending,
                        excluded
                );
    }

    @Test
    @DisplayName("현재 가입했어도 생성 당시 대상이 아니면 목록에서 제외하고 내 확인 여부는 false다")
    void findSummary_success_laterJoinerNotRequired() {
        // given
        target(
                true,
                DocumentFixtures.CREATED_AT
        );
        // when & then
        assertThat(summary(viewerId).confirmedByMe()).isFalse();
        assertThat(
                query.findPage(
                        workspaceId,
                        documentId,
                        50,
                        null
                )
        ).extracting(DocumentConfirmationItemResult::memberId)
                .doesNotContain(viewerId);
    }

    @Test
    @DisplayName("없는 문서와 다른 Workspace 문서는 대상 0명인 문서와 구분한다")
    void findSummary_failure_missingOrForeignDocument() {
        // given
        fixtures.target(
                documentId,
                viewerId,
                null
        );
        long otherWorkspace = fixtures.saveWorkspace();
        // when & then
        assertThat(
                query.findSummary(
                        workspaceId,
                        Long.MAX_VALUE,
                        viewerId
                )
        ).isEmpty();
        assertThat(
                query.findSummary(
                        otherWorkspace,
                        documentId,
                        viewerId
                )
        ).isEmpty();
        assertThat(
                query.findPage(
                        otherWorkspace,
                        documentId,
                        50,
                        null
                )
        ).isEmpty();
    }

    @Test
    @DisplayName("상태 순서와 같은 상태의 대상 ID 순서로 여러 페이지를 빠짐없이 연결한다")
    void findPage_success_stateBoundaries() {
        // given
        long pending = target(
                true,
                null
        );
        long excluded = target(
                false,
                null
        );
        long confirmed = target(
                true,
                DocumentFixtures.CREATED_AT
        );
        long departedConfirmed = target(
                false,
                DocumentFixtures.CREATED_AT
        );
        List<Long> actualIds = new ArrayList<>();
        DocumentConfirmationCursor cursor = null;
        // when
        for (int pageNumber = 0; pageNumber < 4; pageNumber++) {
            List<DocumentConfirmationItemResult> page = query.findPage(
                    workspaceId,
                    documentId,
                    1,
                    cursor
            );
            DocumentConfirmationItemResult item = page.getFirst();
            actualIds.add(item.memberId());
            cursor = DocumentConfirmationCursor.of(
                    workspaceId,
                    documentId,
                    viewerId,
                    item.state(),
                    item.memberId()
            );
        }
        // then
        assertThat(actualIds).containsExactly(
                confirmed,
                departedConfirmed,
                pending,
                excluded
        );
        assertThat(
                query.findPage(
                        workspaceId,
                        documentId,
                        1,
                        cursor
                )
        ).isEmpty();
    }

    @Test
    @DisplayName("같은 상태에서 커서 경계 이후의 더 큰 대상 ID만 반환한다")
    void findPage_success_sameStateBoundary() {
        // given
        long first = target(
                true,
                null
        );
        long second = target(
                true,
                null
        );
        DocumentConfirmationCursor cursor = DocumentConfirmationCursor.of(
                workspaceId,
                documentId,
                viewerId,
                DocumentConfirmationState.PENDING,
                first
        );
        // when & then
        assertThat(
                query.findPage(
                        workspaceId,
                        documentId,
                        1,
                        cursor
                )
        ).extracting(DocumentConfirmationItemResult::memberId)
                .containsExactly(second);
    }

    @Test
    @DisplayName("탈퇴 후 재가입 이력은 한 대상만 반환하고 활성 미확인으로 집계한다")
    void findPage_success_rejoinedTargetNotDuplicated() {
        // given
        long rejoined = target(
                false,
                null
        );
        fixtures.join(
                workspaceId,
                rejoined
        );
        // when & then
        assertThat(summary(viewerId).summary()).isEqualTo(
                new DocumentConfirmationSummaryResult(
                        0,
                        1,
                        0
                )
        );
        assertThat(
                query.findPage(
                        workspaceId,
                        documentId,
                        50,
                        null
                )
        ).extracting(DocumentConfirmationItemResult::state)
                .containsExactly(DocumentConfirmationState.PENDING);
    }

    @Test
    @DisplayName("현재 프로필과 실제 확인 시각을 반환하고 알 수 없는 프로필·확인 시각은 null이다")
    void findPage_success_profileAndNullableFields() {
        // given
        long confirmed = target(
                true,
                DocumentFixtures.CREATED_AT
        );
        target(
                false,
                null
        );
        jdbc.sql("UPDATE members SET profile_image_url = :url WHERE id = :id")
                .param(
                        "url",
                        "https://example.com/avatar.png"
                )
                .param(
                        "id",
                        confirmed
                )
                .update();
        // when
        List<DocumentConfirmationItemResult> page = query.findPage(
                workspaceId,
                documentId,
                50,
                null
        );
        // then
        assertThat(
                page.getFirst()
                        .nickname()
        ).startsWith("대상");
        assertThat(
                page.getFirst()
                        .profileImageUrl()
        ).isEqualTo("https://example.com/avatar.png");
        assertThat(
                page.getFirst()
                        .confirmedAt()
        ).isEqualTo(DocumentFixtures.CREATED_AT);
        assertThat(
                page.getLast()
                        .profileImageUrl()
        ).isNull();
        assertThat(
                page.getLast()
                        .confirmedAt()
        ).isNull();
        assertThat(
                page.getLast()
                        .state()
        ).isEqualTo(DocumentConfirmationState.EXCLUDED);
    }

    private DocumentConfirmationOverviewResult summary(long memberId) {
        return query.findSummary(
                workspaceId,
                documentId,
                memberId
        )
                .orElseThrow();
    }

    private long target(
            boolean active,
            Instant confirmedAt
    ) {
        long memberId = fixtures.saveMember("대상" + System.nanoTime());
        fixtures.join(
                workspaceId,
                memberId
        );
        fixtures.target(
                documentId,
                memberId,
                confirmedAt
        );
        if (!active) {
            fixtures.leave(
                    workspaceId,
                    memberId
            );
        }
        return memberId;
    }
}
