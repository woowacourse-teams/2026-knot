package com.knot.backend.recording.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import com.knot.backend.recording.application.dto.query.RecentRecordingListParameters;
import com.knot.backend.recording.application.dto.result.RecentRecordingListResult;
import com.knot.backend.recording.application.dto.result.RecentRecordingRowResult;
import com.knot.backend.recording.application.dto.result.RecordingDocumentProcessingStatus;
import com.knot.backend.recording.application.dto.result.RecordingDocumentResult;
import com.knot.backend.testsupport.RecordingHistoryFixture;
import com.knot.backend.testsupport.TestApplicationProperties;
import com.knot.backend.testsupport.TestcontainersConfiguration;
import com.knot.backend.workspace.domain.WorkspaceErrorCode;
import com.knot.backend.workspace.domain.WorkspaceException;
import java.time.Instant;
import java.util.ArrayList;
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
@SpringBootTest(properties = {"knot.transcription.enabled=true", "knot.transcription.token=test-token"})
@TestConstructor(autowireMode = AutowireMode.ALL)
class RecentRecordingListServiceIntegrationTest {
    private static final Instant STARTED_AT = Instant.parse("2026-10-24T09:00:00.123456Z");

    private final RecentRecordingListService recentRecordingListService;
    private final RecordingDocumentService recordingDocumentService;
    private final JdbcClient jdbcClient;
    private final RecordingHistoryFixture fixture;
    private long workspaceId;
    private long memberId;
    private long teammateId;

    RecentRecordingListServiceIntegrationTest(
            RecentRecordingListService recentRecordingListService,
            RecordingDocumentService recordingDocumentService,
            JdbcClient jdbcClient
    ) {
        this.recentRecordingListService = recentRecordingListService;
        this.recordingDocumentService = recordingDocumentService;
        this.jdbcClient = jdbcClient;
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
    @DisplayName("다른 멤버가 시작한 녹음은 보이고 다른 Workspace의 녹음은 보이지 않는다")
    void find_success_workspaceScope() {
        // given
        long otherWorkspaceId = fixture.workspace("다른 팀");
        fixture.join(
                otherWorkspaceId,
                memberId
        );
        long mine = saved(
                memberId,
                0
        );
        long teammate = saved(
                teammateId,
                1
        );
        fixture.savedRecording(
                otherWorkspaceId,
                memberId,
                STARTED_AT.plusSeconds(2)
        );

        // when
        List<Long> ids = recordingIds(firstPage(20));

        // then
        assertThat(ids).containsExactly(
                teammate,
                mine
        );
    }

    @Test
    @DisplayName("시작한 Member가 Workspace를 나가도 저장된 녹음은 남는다")
    void find_success_starterLeft() {
        // given
        long recordingId = saved(
                teammateId,
                0
        );
        jdbcClient.sql("UPDATE workspace_members SET left_at = joined_at WHERE member_id = :memberId")
                .param(
                        "memberId",
                        teammateId
                )
                .update();

        // when
        List<Long> ids = recordingIds(firstPage(20));

        // then
        assertThat(ids).containsExactly(recordingId);
    }

    @Test
    @DisplayName("음성 저장이 끝났거나 최종화 대기·진행 중인 끝난 녹음은 처리 결과와 관계없이 담는다")
    void find_success_includesSavedRecordingsRegardlessOfResult() {
        // given
        long processing = saved(
                memberId,
                0
        );
        long transcriptionFailed = saved(
                memberId,
                1
        );
        fixture.transcriptionFailed(transcriptionFailed);
        long noContent = saved(
                memberId,
                2
        );
        fixture.batch(
                noContent,
                "NO_CONTENT",
                "NO_CONTENT"
        );
        long documentFailed = saved(
                memberId,
                3
        );
        fixture.failedJob(
                fixture.batch(
                        documentFailed,
                        "TOPICS_REGISTERED",
                        "FAILED"
                )
        );
        long completed = saved(
                memberId,
                4
        );
        fixture.batch(
                completed,
                "TOPICS_REGISTERED",
                "SUCCEEDED"
        );
        long finalizationPending = pcm(5);
        long finalizationQueued = pcm(6);
        fixture.finalization(
                finalizationQueued,
                "QUEUED"
        );
        long finalizationRunning = pcm(7);
        fixture.finalization(
                finalizationRunning,
                "PROCESSING"
        );
        long finalized = pcm(8);
        fixture.finalization(
                finalized,
                "READY"
        );

        // when
        List<Long> ids = recordingIds(firstPage(20));

        // then
        assertThat(ids).containsExactly(
                finalized,
                finalizationRunning,
                finalizationQueued,
                finalizationPending,
                completed,
                documentFailed,
                noContent,
                transcriptionFailed,
                processing
        );
    }

    @Test
    @DisplayName("녹음 중·일시정지·폐기와 음성 저장에 실패하거나 아직 올리지 못한 녹음은 담지 않는다")
    void find_success_excludesUnsavedRecordings() {
        // given
        long recording = saved(
                memberId,
                0
        );
        fixture.changeStatus(
                recording,
                "RECORDING"
        );
        long paused = saved(
                teammateId,
                1
        );
        fixture.changeStatus(
                paused,
                "PAUSED"
        );
        long discarded = saved(
                memberId,
                2
        );
        fixture.changeStatus(
                discarded,
                "DISCARDED"
        );
        long saveFailed = pcm(3);
        fixture.finalization(
                saveFailed,
                "FAILED"
        );
        long reserved = fixture.endedRecording(
                workspaceId,
                memberId,
                STARTED_AT.plusSeconds(4),
                "FINAL_WEBM_V1"
        );
        fixture.reservedUpload(reserved);
        fixture.endedRecording(
                workspaceId,
                memberId,
                STARTED_AT.plusSeconds(5),
                "FINAL_WEBM_V1"
        );

        // when
        RecentRecordingListResult result = firstPage(20);

        // then
        assertThat(result.items()).isEmpty();
        assertThat(result.nextCursor()).isNull();
    }

    @Test
    @DisplayName("음성 저장 재시도가 성공하면 그때부터 목록에 담는다")
    void find_success_afterAudioSaveRetry() {
        // given
        long recordingId = pcm(0);
        fixture.finalization(
                recordingId,
                "FAILED"
        );
        List<Long> beforeRetry = recordingIds(firstPage(20));
        fixture.changeFinalization(
                recordingId,
                "READY"
        );

        // when
        List<Long> afterRetry = recordingIds(firstPage(20));

        // then
        assertThat(beforeRetry).isEmpty();
        assertThat(afterRetry).containsExactly(recordingId);
    }

    @Test
    @DisplayName("같은 시작 시각의 녹음은 ID 내림차순이고 페이지 경계에서 빠지거나 겹치지 않는다")
    void find_success_pagesWithoutGapOrDuplicate() {
        // given
        List<Long> expected = new ArrayList<>();
        for (int index = 0; index < 5; index++) {
            expected.addFirst(
                    fixture.savedRecording(
                            workspaceId,
                            memberId,
                            STARTED_AT
                    )
            );
        }
        expected.addFirst(
                saved(
                        memberId,
                        60
                )
        );

        // when
        List<Long> collected = new ArrayList<>();
        List<Integer> pageSizes = new ArrayList<>();
        String cursor = null;
        do {
            RecentRecordingListResult page = recentRecordingListService.find(
                    workspaceId,
                    teammateId,
                    RecentRecordingListParameters.of(
                            cursor,
                            2
                    )
            );
            collected.addAll(recordingIds(page));
            pageSizes.add(
                    page.items()
                            .size()
            );
            cursor = page.nextCursor();
        } while (cursor != null);

        // then
        assertThat(collected).containsExactlyElementsOf(expected);
        assertThat(pageSizes).containsExactly(
                2,
                2,
                2
        );
    }

    @Test
    @DisplayName("공개할 AI 제목이 있으면 그 제목, 없거나 NO_CONTENT면 시작한 Member 닉네임의 대체 이름을 준다")
    void find_success_titles() {
        // given
        long titled = saved(
                teammateId,
                0
        );
        fixture.batch(
                titled,
                "TOPICS_REGISTERED",
                "SUCCEEDED"
        );
        fixture.title(
                titled,
                "회원 탈퇴 정책 논의"
        );
        long untitled = saved(
                teammateId,
                1
        );
        fixture.batch(
                untitled,
                "TOPICS_REGISTERED",
                "RUNNING"
        );
        long noContent = saved(
                memberId,
                2
        );
        fixture.batch(
                noContent,
                "NO_CONTENT",
                "NO_CONTENT"
        );
        fixture.title(
                noContent,
                "공개하면 안 되는 제목"
        );

        // when
        List<RecentRecordingRowResult> items = firstPage(20).items();

        // then
        assertThat(items).extracting(RecentRecordingRowResult::title)
                .containsExactly(
                        "루덴스 님의 녹음",
                        "흑곰 님의 녹음",
                        "회원 탈퇴 정책 논의"
                );
        assertThat(
                items.getFirst()
                        .startedAt()
        ).isEqualTo(STARTED_AT.plusSeconds(2));
        assertThat(
                items.getFirst()
                        .recordingDurationSeconds()
        ).isEqualTo(61);
    }

    @Test
    @DisplayName("행마다 녹음 문서와 같은 규칙으로 처리 상태, 재시도 가능 여부, 재시도할 Job ID를 한 번에 계산한다")
    void find_success_processingStatusPerRow() {
        // given
        long beforeTranscription = saved(
                memberId,
                0
        );
        long documentRunning = saved(
                teammateId,
                1
        );
        fixture.batch(
                documentRunning,
                "TOPICS_REGISTERED",
                "RUNNING"
        );
        long transcriptionRetryable = saved(
                memberId,
                2
        );
        fixture.transcriptionFailed(transcriptionRetryable);
        long transcriptionAudioDeleted = saved(
                teammateId,
                3
        );
        fixture.transcriptionFailed(transcriptionAudioDeleted);
        fixture.deleteAudio(transcriptionAudioDeleted);
        long documentRetryable = saved(
                memberId,
                4
        );
        long documentJob = fixture.failedJob(
                fixture.batch(
                        documentRetryable,
                        "TOPICS_REGISTERED",
                        "FAILED"
                )
        );
        long classificationFailed = saved(
                teammateId,
                5
        );
        long classificationJob = fixture.failedJob(
                fixture.batch(
                        classificationFailed,
                        "WAITING_CLASSIFICATION",
                        "FAILED"
                )
        );
        long documentWithoutRetryableJob = saved(
                memberId,
                6
        );
        fixture.batch(
                documentWithoutRetryableJob,
                "TOPICS_REGISTERED",
                "FAILED"
        );
        long noContent = saved(
                teammateId,
                7
        );
        fixture.batch(
                noContent,
                "NO_CONTENT",
                "NO_CONTENT"
        );
        long completed = saved(
                memberId,
                8
        );
        fixture.batch(
                completed,
                "TOPICS_REGISTERED",
                "SUCCEEDED"
        );

        // when
        List<RecentRecordingRowResult> rows = firstPage(20).items();

        // then
        assertThat(rows).extracting(
                RecentRecordingRowResult::recordingId,
                RecentRecordingRowResult::processingStatus,
                RecentRecordingRowResult::retryable,
                RecentRecordingRowResult::retryableJobIds
        )
                .containsExactly(
                        tuple(
                                completed,
                                RecordingDocumentProcessingStatus.COMPLETED,
                                false,
                                List.of()
                        ),
                        tuple(
                                noContent,
                                RecordingDocumentProcessingStatus.NO_CONTENT,
                                false,
                                List.of()
                        ),
                        tuple(
                                documentWithoutRetryableJob,
                                RecordingDocumentProcessingStatus.DOCUMENT_FAILED,
                                false,
                                List.of()
                        ),
                        tuple(
                                classificationFailed,
                                RecordingDocumentProcessingStatus.DOCUMENT_FAILED,
                                true,
                                List.of(classificationJob)
                        ),
                        tuple(
                                documentRetryable,
                                RecordingDocumentProcessingStatus.DOCUMENT_FAILED,
                                true,
                                List.of(documentJob)
                        ),
                        tuple(
                                transcriptionAudioDeleted,
                                RecordingDocumentProcessingStatus.TRANSCRIPTION_FAILED,
                                false,
                                List.of()
                        ),
                        tuple(
                                transcriptionRetryable,
                                RecordingDocumentProcessingStatus.TRANSCRIPTION_FAILED,
                                true,
                                List.of()
                        ),
                        tuple(
                                documentRunning,
                                RecordingDocumentProcessingStatus.PROCESSING,
                                false,
                                List.of()
                        ),
                        tuple(
                                beforeTranscription,
                                RecordingDocumentProcessingStatus.PROCESSING,
                                false,
                                List.of()
                        )
                );
        assertThat(rows).allSatisfy(row -> assertSameAsDocument(row));
    }

    @Test
    @DisplayName("다음 커서로 이어 받은 행도 같은 규칙으로 처리 상태를 준다")
    void find_success_processingStatusOnNextPage() {
        // given
        long failed = saved(
                memberId,
                0
        );
        fixture.transcriptionFailed(failed);
        long completed = saved(
                memberId,
                1
        );
        fixture.batch(
                completed,
                "TOPICS_REGISTERED",
                "SUCCEEDED"
        );
        RecentRecordingListResult first = firstPage(1);

        // when
        RecentRecordingListResult next = recentRecordingListService.find(
                workspaceId,
                memberId,
                RecentRecordingListParameters.of(
                        first.nextCursor(),
                        1
                )
        );

        // then
        assertThat(first.items()).extracting(
                RecentRecordingRowResult::recordingId,
                RecentRecordingRowResult::processingStatus
        )
                .containsExactly(
                        tuple(
                                completed,
                                RecordingDocumentProcessingStatus.COMPLETED
                        )
                );
        assertThat(next.items()).extracting(
                RecentRecordingRowResult::recordingId,
                RecentRecordingRowResult::processingStatus,
                RecentRecordingRowResult::retryable
        )
                .containsExactly(
                        tuple(
                                failed,
                                RecordingDocumentProcessingStatus.TRANSCRIPTION_FAILED,
                                true
                        )
                );
    }

    @Test
    @DisplayName("현재 Workspace 멤버가 아니면 녹음 목록을 주지 않는다")
    void find_fail_notMember() {
        // given
        long outsiderId = fixture.member("외부인");
        saved(
                memberId,
                0
        );

        // when & then
        assertThatThrownBy(
                () -> recentRecordingListService.find(
                        workspaceId,
                        outsiderId,
                        RecentRecordingListParameters.of(
                                null,
                                null
                        )
                )
        ).isInstanceOf(WorkspaceException.class)
                .extracting("errorCode")
                .isEqualTo(WorkspaceErrorCode.WORKSPACE_ACCESS_DENIED);
    }

    private long saved(
            long starterId,
            int secondsAfterStart
    ) {
        return fixture.savedRecording(
                workspaceId,
                starterId,
                STARTED_AT.plusSeconds(secondsAfterStart)
        );
    }

    private long pcm(int secondsAfterStart) {
        return fixture.endedRecording(
                workspaceId,
                memberId,
                STARTED_AT.plusSeconds(secondsAfterStart),
                "PCM16_CHUNKS_V1"
        );
    }

    private RecentRecordingListResult firstPage(int size) {
        return recentRecordingListService.find(
                workspaceId,
                memberId,
                RecentRecordingListParameters.of(
                        null,
                        size
                )
        );
    }

    private void assertSameAsDocument(RecentRecordingRowResult row) {
        RecordingDocumentResult document = recordingDocumentService.find(
                workspaceId,
                memberId,
                row.recordingId()
        );
        assertThat(row.processingStatus()).isEqualTo(document.processingStatus());
        assertThat(row.retryable()).isEqualTo(document.retryable());
        assertThat(row.retryableJobIds()).isEqualTo(document.retryableJobIds());
    }

    private List<Long> recordingIds(RecentRecordingListResult result) {
        return result.items()
                .stream()
                .map(RecentRecordingRowResult::recordingId)
                .toList();
    }
}
