package com.knot.backend.document.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.knot.backend.document.application.DocumentRecordingListQuery;
import com.knot.backend.document.application.dto.result.DocumentRecordingDocumentResult;
import com.knot.backend.document.application.dto.result.DocumentRecordingItemResult;
import com.knot.backend.recording.application.RecentRecordingQuery;
import com.knot.backend.recording.application.dto.result.RecentRecordingItemResult;
import com.knot.backend.recording.domain.model.RecentRecordingCursor;
import com.knot.backend.testsupport.RecordingHistoryFixture;
import com.knot.backend.testsupport.TestApplicationProperties;
import com.knot.backend.testsupport.TestcontainersConfiguration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestConstructor;
import org.springframework.test.context.TestConstructor.AutowireMode;

@Tag("integration")
@Import(TestcontainersConfiguration.class)
@TestApplicationProperties
@SpringBootTest
@TestConstructor(autowireMode = AutowireMode.ALL)
class DocumentRecordingListQueryIntegrationTest {
    private static final Instant STARTED_AT = Instant.parse("2026-10-24T09:00:00Z");
    private static final Instant CREATED_AT = Instant.parse("2026-10-24T10:00:00Z");

    private final DocumentRecordingListQuery documentRecordingListQuery;
    private final RecentRecordingQuery recentRecordingQuery;
    private final RecordingHistoryFixture fixture;
    private long workspaceId;
    private long memberId;
    private long teammateId;

    DocumentRecordingListQueryIntegrationTest(
            DocumentRecordingListQuery documentRecordingListQuery,
            RecentRecordingQuery recentRecordingQuery,
            JdbcClient jdbcClient
    ) {
        this.documentRecordingListQuery = documentRecordingListQuery;
        this.recentRecordingQuery = recentRecordingQuery;
        this.fixture = new RecordingHistoryFixture(jdbcClient);
    }

    @BeforeEach
    void setUp() {
        fixture.clear();
        memberId = fixture.member("루덴스");
        teammateId = fixture.member("흑곰");
        workspaceId = fixture.workspace("녹음 팀");
        fixture.join(
                workspaceId,
                memberId
        );
        fixture.join(
                workspaceId,
                teammateId
        );
    }

    @Test
    @DisplayName("문서가 있는 녹음만 시작 최신순으로 묶고 묶음 안 문서는 생성 오름차순이다")
    void findPage_success_groupsRecordingsWithDocuments() {
        // given
        long older = fixture.savedRecording(
                workspaceId,
                memberId,
                STARTED_AT
        );
        long olderBatch = registered(older);
        long first = document(
                olderBatch,
                CREATED_AT
        );
        long latest = document(
                olderBatch,
                CREATED_AT.plusSeconds(10)
        );
        long sameTime = document(
                olderBatch,
                CREATED_AT
        );
        long sameStart = fixture.savedRecording(
                workspaceId,
                memberId,
                STARTED_AT
        );
        long sameStartDocument = document(
                registered(sameStart),
                CREATED_AT
        );
        long teammateRecording = fixture.savedRecording(
                workspaceId,
                teammateId,
                STARTED_AT.plusSeconds(60)
        );
        long teammateDocument = document(
                registered(teammateRecording),
                CREATED_AT
        );
        registered(
                fixture.savedRecording(
                        workspaceId,
                        memberId,
                        STARTED_AT.plusSeconds(120)
                )
        );
        fixture.savedRecording(
                workspaceId,
                memberId,
                STARTED_AT.plusSeconds(180)
        );
        long otherWorkspaceId = fixture.workspace("다른 팀");
        fixture.join(
                otherWorkspaceId,
                memberId
        );
        document(
                registered(
                        fixture.savedRecording(
                                otherWorkspaceId,
                                memberId,
                                STARTED_AT.plusSeconds(240)
                        )
                ),
                CREATED_AT
        );

        // when
        List<DocumentRecordingItemResult> page = documentRecordingListQuery.findPage(
                workspaceId,
                null,
                10
        );

        // then
        assertThat(page).extracting(DocumentRecordingItemResult::recordingId)
                .containsExactly(
                        teammateRecording,
                        sameStart,
                        older
                );
        assertThat(documentIds(page.get(0))).containsExactly(teammateDocument);
        assertThat(documentIds(page.get(1))).containsExactly(sameStartDocument);
        assertThat(documentIds(page.get(2))).containsExactly(
                first,
                sameTime,
                latest
        );
    }

    @Test
    @DisplayName("녹음의 표시 제목·시작 시각·길이는 최근 녹음 목록과 같고 문서는 제목과 요약을 준다")
    void findPage_success_sameRecordingFieldsAsRecentRecordings() {
        // given
        long titled = fixture.savedRecording(
                workspaceId,
                teammateId,
                STARTED_AT.plusSeconds(60)
        );
        long titledBatch = registered(titled);
        fixture.title(
                titled,
                "회원 탈퇴 정책 논의"
        );
        long summarized = fixture.document(
                titledBatch,
                "탈퇴 데이터 보존 범위",
                "탈퇴 후 30일간 데이터를 보관하기로 했다.",
                CREATED_AT
        );
        long untitled = fixture.savedRecording(
                workspaceId,
                teammateId,
                STARTED_AT
        );
        long withoutSummary = fixture.document(
                registered(untitled),
                "탈퇴 안내 문구",
                null,
                CREATED_AT
        );

        // when
        List<DocumentRecordingItemResult> page = documentRecordingListQuery.findPage(
                workspaceId,
                null,
                10
        );

        // then
        assertThat(page).extracting(DocumentRecordingItemResult::title)
                .containsExactly(
                        "회원 탈퇴 정책 논의",
                        "흑곰 님의 녹음"
                );
        assertThat(page.get(0)).usingRecursiveComparison()
                .comparingOnlyFields(
                        "recordingId",
                        "title",
                        "startedAt",
                        "recordingDurationSeconds"
                )
                .isEqualTo(recentRecording(titled));
        assertThat(page.get(1)).usingRecursiveComparison()
                .comparingOnlyFields(
                        "recordingId",
                        "title",
                        "startedAt",
                        "recordingDurationSeconds"
                )
                .isEqualTo(recentRecording(untitled));
        assertThat(
                page.get(0)
                        .documents()
        ).containsExactly(
                new DocumentRecordingDocumentResult(
                        summarized,
                        "탈퇴 데이터 보존 범위",
                        "탈퇴 후 30일간 데이터를 보관하기로 했다."
                )
        );
        assertThat(
                page.get(1)
                        .documents()
        ).containsExactly(
                new DocumentRecordingDocumentResult(
                        withoutSummary,
                        "탈퇴 안내 문구",
                        null
                )
        );
    }

    @Test
    @DisplayName("cursor가 있으면 그 녹음 다음부터 limit개의 녹음을 준다")
    void findPage_success_afterCursor() {
        // given
        long oldest = recordingWithDocument(STARTED_AT);
        long middle = recordingWithDocument(STARTED_AT.plusSeconds(60));
        long newest = recordingWithDocument(STARTED_AT.plusSeconds(120));
        RecentRecordingCursor cursor = RecentRecordingCursor.of(
                workspaceId,
                STARTED_AT.plusSeconds(60),
                middle
        );

        // when
        List<DocumentRecordingItemResult> firstPage = documentRecordingListQuery.findPage(
                workspaceId,
                null,
                2
        );
        List<DocumentRecordingItemResult> nextPage = documentRecordingListQuery.findPage(
                workspaceId,
                cursor,
                2
        );

        // then
        assertThat(firstPage).extracting(DocumentRecordingItemResult::recordingId)
                .containsExactly(
                        newest,
                        middle
                );
        assertThat(nextPage).extracting(DocumentRecordingItemResult::recordingId)
                .containsExactly(oldest);
    }

    private long registered(long recordingId) {
        return fixture.batch(
                recordingId,
                "TOPICS_REGISTERED",
                "SUCCEEDED"
        );
    }

    private long document(
            long batchId,
            Instant createdAt
    ) {
        return fixture.document(
                batchId,
                "회원 탈퇴 정책",
                "탈퇴 정책을 정했다.",
                createdAt
        );
    }

    private long recordingWithDocument(Instant startedAt) {
        long recordingId = fixture.savedRecording(
                workspaceId,
                memberId,
                startedAt
        );
        document(
                registered(recordingId),
                CREATED_AT
        );
        return recordingId;
    }

    private RecentRecordingItemResult recentRecording(long recordingId) {
        return recentRecordingQuery.findItem(
                workspaceId,
                recordingId
        )
                .orElseThrow();
    }

    private List<Long> documentIds(DocumentRecordingItemResult item) {
        return item.documents()
                .stream()
                .map(DocumentRecordingDocumentResult::id)
                .toList();
    }
}
