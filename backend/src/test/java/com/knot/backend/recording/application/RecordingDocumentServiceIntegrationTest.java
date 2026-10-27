package com.knot.backend.recording.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.groups.Tuple.tuple;

import com.knot.backend.document.DocumentFixtures;
import com.knot.backend.global.exception.ErrorCode;
import com.knot.backend.recording.application.dto.result.RecentRecordingItemResult;
import com.knot.backend.recording.application.dto.result.RecordingDocumentProcessingStatus;
import com.knot.backend.recording.application.dto.result.RecordingDocumentResult;
import com.knot.backend.recording.application.dto.result.RecordingMeetingSummaryResult;
import com.knot.backend.recording.domain.RecordingErrorCode;
import com.knot.backend.recording.domain.RecordingException;
import com.knot.backend.recording.domain.model.MeetingSummaryStatus;
import com.knot.backend.testsupport.RecordingHistoryFixture;
import com.knot.backend.testsupport.TestApplicationProperties;
import com.knot.backend.testsupport.TestcontainersConfiguration;
import com.knot.backend.workspace.domain.WorkspaceErrorCode;
import com.knot.backend.workspace.domain.WorkspaceException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.assertj.core.groups.Tuple;
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
class RecordingDocumentServiceIntegrationTest {
    private static final Instant STARTED_AT = Instant.parse("2026-10-24T09:00:00Z");

    private final RecordingDocumentService recordingDocumentService;
    private final RecentRecordingQuery recentRecordingQuery;
    private final MeetingSummaryGenerationService meetingSummaryGenerationService;
    private final JdbcClient jdbcClient;
    private final RecordingHistoryFixture fixture;
    private final DocumentFixtures documentFixtures;
    private long workspaceId;
    private long memberId;
    private long teammateId;

    RecordingDocumentServiceIntegrationTest(
            RecordingDocumentService recordingDocumentService,
            RecentRecordingQuery recentRecordingQuery,
            MeetingSummaryGenerationService meetingSummaryGenerationService,
            JdbcClient jdbcClient
    ) {
        this.recordingDocumentService = recordingDocumentService;
        this.recentRecordingQuery = recentRecordingQuery;
        this.meetingSummaryGenerationService = meetingSummaryGenerationService;
        this.jdbcClient = jdbcClient;
        this.fixture = new RecordingHistoryFixture(jdbcClient);
        this.documentFixtures = new DocumentFixtures(jdbcClient);
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
    @DisplayName("녹음을 시작하지 않은 멤버도 팀원 녹음의 녹음 문서를 최근 녹음 목록과 같은 제목·시작 시각·길이로 연다")
    void find_success_teammateRecordingSameAsRecentRecordings() {
        // given
        long titled = saved(
                teammateId,
                0
        );
        succeededJobs(titled);
        fixture.title(
                titled,
                "회원 탈퇴 정책 논의"
        );
        long untitled = saved(
                teammateId,
                1
        );

        // when
        List<RecordingDocumentResult> documents = List.of(
                find(titled),
                find(untitled)
        );

        // then
        assertThat(documents).extracting(RecordingDocumentResult::title)
                .containsExactly(
                        "회원 탈퇴 정책 논의",
                        "흑곰 님의 녹음"
                );
        assertThat(documents).extracting(
                RecordingDocumentResult::recordingId,
                RecordingDocumentResult::title,
                RecordingDocumentResult::startedAt,
                RecordingDocumentResult::recordingDurationSeconds
        )
                .containsExactlyInAnyOrderElementsOf(
                        recentRecordingQuery.findPage(
                                workspaceId,
                                null,
                                100
                        )
                                .stream()
                                .map(this::listed)
                                .toList()
                );
        assertThat(
                documents.getFirst()
                        .recordingDurationSeconds()
        ).isEqualTo(61);
    }

    @Test
    @DisplayName("음성 최종화·전사·주제별 문서 작업에 대기·실행이 있으면 실패 Job이 있어도 PROCESSING이다")
    void find_success_processingFirst() {
        // given
        long beforeTranscription = saved(
                memberId,
                0
        );
        long finalizationPending = fixture.endedRecording(
                workspaceId,
                memberId,
                STARTED_AT.plusSeconds(1),
                "PCM16_CHUNKS_V1"
        );
        long requeued = saved(
                teammateId,
                2
        );
        long transcriptId = documentFixtures.saveTranscript(requeued);
        failedJob(transcriptId);
        documentFixtures.saveJob(
                transcriptId,
                "QUEUED"
        );

        // when
        List<RecordingDocumentResult> documents = List.of(
                beforeTranscription,
                finalizationPending,
                requeued
        )
                .stream()
                .map(this::find)
                .toList();

        // then
        assertThat(documents).extracting(
                RecordingDocumentResult::processingStatus,
                RecordingDocumentResult::retryable,
                RecordingDocumentResult::retryableJobIds
        )
                .containsOnly(
                        tuple(
                                RecordingDocumentProcessingStatus.PROCESSING,
                                false,
                                List.of()
                        )
                );
    }

    @Test
    @DisplayName("전사 실패는 전사 재시도 접수 조건으로 retryable을 계산하고 요약은 null이다")
    void find_success_transcriptionFailed() {
        // given
        long retryable = saved(
                memberId,
                0
        );
        fixture.transcriptionFailed(retryable);
        long audioDeleted = saved(
                teammateId,
                1
        );
        fixture.transcriptionFailed(audioDeleted);
        fixture.deleteAudio(audioDeleted);

        // when
        RecordingDocumentResult retryableDocument = find(retryable);
        RecordingDocumentResult audioDeletedDocument = find(audioDeleted);

        // then
        assertThat(retryableDocument.processingStatus())
                .isEqualTo(RecordingDocumentProcessingStatus.TRANSCRIPTION_FAILED);
        assertThat(retryableDocument.retryable()).isTrue();
        assertThat(retryableDocument.retryableJobIds()).isEmpty();
        assertThat(retryableDocument.meetingSummary()).isNull();
        assertThat(audioDeletedDocument.processingStatus())
                .isEqualTo(RecordingDocumentProcessingStatus.TRANSCRIPTION_FAILED);
        assertThat(audioDeletedDocument.retryable()).isFalse();
    }

    @Test
    @DisplayName("문서 생성 실패는 홈 표시 기한이 지난 실패까지 지금 접수할 수 있는 FAILED Job만 중복 없이 준다")
    void find_success_documentFailed() {
        // given
        long recordingId = saved(
                teammateId,
                0
        );
        long transcriptId = documentFixtures.saveTranscript(recordingId);
        long recentFailure = failedJob(transcriptId);
        Instant longAgo = Instant.now()
                .minus(Duration.ofDays(30));
        long expiredFailure = documentFixtures.saveJob(
                transcriptId,
                "FAILED",
                longAgo,
                longAgo
        );
        documentFixtures.saveJob(
                transcriptId,
                "SUCCEEDED"
        );

        // when
        RecordingDocumentResult document = find(recordingId);

        // then
        assertThat(document.processingStatus()).isEqualTo(RecordingDocumentProcessingStatus.DOCUMENT_FAILED);
        assertThat(document.retryable()).isTrue();
        assertThat(document.retryableJobIds()).containsExactly(
                recentFailure,
                expiredFailure
        );
    }

    @Test
    @DisplayName("NO_CONTENT는 재시도와 AI 제목·요약이 없고 모든 주제별 Job이 성공하면 COMPLETED다")
    void find_success_noContentAndCompleted() {
        // given
        long noContent = saved(
                memberId,
                0
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
        long completed = saved(
                memberId,
                1
        );
        succeededJobs(completed);

        // when
        RecordingDocumentResult noContentDocument = find(noContent);
        RecordingDocumentResult completedDocument = find(completed);

        // then
        assertThat(noContentDocument.processingStatus()).isEqualTo(RecordingDocumentProcessingStatus.NO_CONTENT);
        assertThat(noContentDocument.retryable()).isFalse();
        assertThat(noContentDocument.retryableJobIds()).isEmpty();
        assertThat(noContentDocument.title()).isEqualTo("루덴스 님의 녹음");
        assertThat(noContentDocument.meetingSummary()).isNull();
        assertThat(completedDocument.processingStatus()).isEqualTo(RecordingDocumentProcessingStatus.COMPLETED);
        assertThat(completedDocument.retryable()).isFalse();
        assertThat(completedDocument.retryableJobIds()).isEmpty();
    }

    @Test
    @DisplayName("요약 행이 없으면 주제가 등록된 녹음이어도 요약은 null이다")
    void find_success_noMeetingSummaryRow() {
        // given
        long recordingId = saved(
                memberId,
                0
        );
        succeededJobs(recordingId);

        // when
        RecordingDocumentResult document = find(recordingId);

        // then
        assertThat(document.processingStatus()).isEqualTo(RecordingDocumentProcessingStatus.COMPLETED);
        assertThat(document.meetingSummary()).isNull();
    }

    @Test
    @DisplayName("요약 기능을 켜기 전에 끝난 녹음은 요약 접수 뒤에도, 여러 번 열어도 요약이 없다")
    void find_success_endedBeforeMeetingSummaryActivation() {
        // given
        long recordingId = saved(
                memberId,
                0
        );
        succeededJobs(recordingId);
        meetingSummaryGenerationService.registerEligible();

        // when
        RecordingDocumentResult first = find(recordingId);
        RecordingDocumentResult second = find(recordingId);

        // then
        assertThat(first.meetingSummary()).isNull();
        assertThat(second.meetingSummary()).isNull();
        assertThat(meetingSummaryCount(recordingId)).isZero();
    }

    @Test
    @DisplayName("주제 분류에 실패한 녹음은 DOCUMENT_FAILED로 분류 Job을 다시 시도하게 하고 AI 제목·요약을 공개하지 않는다")
    void find_success_classificationFailed() {
        // given
        long recordingId = saved(
                memberId,
                0
        );
        long batchId = fixture.batch(
                recordingId,
                "WAITING_CLASSIFICATION",
                "FAILED"
        );
        fixture.failedJob(batchId);
        fixture.title(
                recordingId,
                "공개하면 안 되는 제목"
        );

        // when
        RecordingDocumentResult document = find(recordingId);

        // then
        assertThat(document.processingStatus()).isEqualTo(RecordingDocumentProcessingStatus.DOCUMENT_FAILED);
        assertThat(document.retryable()).isTrue();
        assertThat(document.retryableJobIds()).hasSize(1);
        assertThat(document.title()).isEqualTo("루덴스 님의 녹음");
        assertThat(document.meetingSummary()).isNull();
    }

    @Test
    @DisplayName("요약은 SUCCEEDED일 때만 본문을 주고, FAILED이고 입력 전사를 쓸 수 있을 때만 retryable이다")
    void find_success_meetingSummary() {
        // given
        long succeeded = saved(
                memberId,
                0
        );
        fixture.meetingSummary(
                succeeded,
                succeededJobs(succeeded),
                "SUCCEEDED"
        );
        long running = saved(
                memberId,
                1
        );
        fixture.meetingSummary(
                running,
                succeededJobs(running),
                "RUNNING"
        );
        long retryableFailure = saved(
                memberId,
                2
        );
        fixture.meetingSummary(
                retryableFailure,
                succeededJobs(retryableFailure),
                "FAILED"
        );
        long unusableInput = saved(
                memberId,
                3
        );
        succeededJobs(unusableInput);
        fixture.meetingSummary(
                unusableInput,
                documentFixtures.saveTranscript(unusableInput),
                "FAILED"
        );

        // when
        Map<Long, RecordingMeetingSummaryResult> summaries = Map.of(
                succeeded,
                find(succeeded).meetingSummary(),
                running,
                find(running).meetingSummary(),
                retryableFailure,
                find(retryableFailure).meetingSummary(),
                unusableInput,
                find(unusableInput).meetingSummary()
        );

        // then
        assertThat(summaries.get(succeeded)).isEqualTo(
                new RecordingMeetingSummaryResult(
                        MeetingSummaryStatus.SUCCEEDED,
                        RecordingHistoryFixture.MEETING_SUMMARY,
                        false
                )
        );
        assertThat(summaries.get(running)).isEqualTo(
                new RecordingMeetingSummaryResult(
                        MeetingSummaryStatus.RUNNING,
                        null,
                        false
                )
        );
        assertThat(summaries.get(retryableFailure)).isEqualTo(
                new RecordingMeetingSummaryResult(
                        MeetingSummaryStatus.FAILED,
                        null,
                        true
                )
        );
        assertThat(summaries.get(unusableInput)).isEqualTo(
                new RecordingMeetingSummaryResult(
                        MeetingSummaryStatus.FAILED,
                        null,
                        false
                )
        );
    }

    @Test
    @DisplayName("요약의 진행·실패는 processingStatus·retryable·retryableJobIds를 바꾸지 않는다")
    void find_success_meetingSummaryDoesNotAffectProcessing() {
        // given
        long completed = saved(
                memberId,
                0
        );
        fixture.meetingSummary(
                completed,
                succeededJobs(completed),
                "FAILED"
        );
        long documentFailed = saved(
                memberId,
                1
        );
        long transcriptId = documentFixtures.saveTranscript(documentFailed);
        long failedJobId = failedJob(transcriptId);
        fixture.meetingSummary(
                documentFailed,
                transcriptId,
                "RUNNING"
        );

        // when
        RecordingDocumentResult completedDocument = find(completed);
        RecordingDocumentResult documentFailedDocument = find(documentFailed);

        // then
        assertThat(completedDocument.processingStatus()).isEqualTo(RecordingDocumentProcessingStatus.COMPLETED);
        assertThat(completedDocument.retryable()).isFalse();
        assertThat(completedDocument.retryableJobIds()).isEmpty();
        assertThat(
                completedDocument.meetingSummary()
                        .retryable()
        ).isTrue();
        assertThat(documentFailedDocument.processingStatus())
                .isEqualTo(RecordingDocumentProcessingStatus.DOCUMENT_FAILED);
        assertThat(documentFailedDocument.retryableJobIds()).containsExactly(failedJobId);
    }

    @Test
    @DisplayName("다른 Workspace·녹음 중·폐기·음성 저장 실패·없는 녹음은 RECORDING_NOT_FOUND다")
    void find_fail_recordingNotOpenable() {
        // given
        long otherWorkspaceId = fixture.workspace("다른 팀");
        fixture.join(
                otherWorkspaceId,
                memberId
        );
        long otherWorkspace = fixture.savedRecording(
                otherWorkspaceId,
                memberId,
                STARTED_AT
        );
        long recording = saved(
                memberId,
                1
        );
        fixture.changeStatus(
                recording,
                "RECORDING"
        );
        long discarded = saved(
                teammateId,
                2
        );
        fixture.changeStatus(
                discarded,
                "DISCARDED"
        );
        long saveFailed = fixture.endedRecording(
                workspaceId,
                memberId,
                STARTED_AT.plusSeconds(3),
                "PCM16_CHUNKS_V1"
        );
        fixture.finalization(
                saveFailed,
                "FAILED"
        );

        // when
        List<ErrorCode> errorCodes = List.of(
                otherWorkspace,
                recording,
                discarded,
                saveFailed,
                Long.MAX_VALUE
        )
                .stream()
                .map(this::errorCode)
                .toList();

        // then
        assertThat(errorCodes).containsOnly(RecordingErrorCode.RECORDING_NOT_FOUND);
    }

    @Test
    @DisplayName("현재 Workspace 멤버가 아니면 녹음 문서를 주지 않는다")
    void find_fail_notMember() {
        // given
        long outsiderId = fixture.member("외부인");
        long recordingId = saved(
                memberId,
                0
        );

        // when & then
        assertThatThrownBy(
                () -> recordingDocumentService.find(
                        workspaceId,
                        outsiderId,
                        recordingId
                )
        ).isInstanceOf(WorkspaceException.class)
                .extracting("errorCode")
                .isEqualTo(WorkspaceErrorCode.WORKSPACE_ACCESS_DENIED);
    }

    @Test
    @DisplayName("양수가 아닌 녹음 ID는 INVALID_RECORDING_DATA다")
    void find_fail_invalidRecordingId() {
        // when & then
        assertThat(errorCode(0)).isEqualTo(RecordingErrorCode.INVALID_RECORDING_DATA);
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

    private long succeededJobs(long recordingId) {
        long transcriptId = documentFixtures.saveTranscript(recordingId);
        documentFixtures.saveJob(
                transcriptId,
                "SUCCEEDED"
        );
        return transcriptId;
    }

    private long failedJob(long transcriptId) {
        Instant failedAt = Instant.now()
                .minus(Duration.ofHours(1));
        return documentFixtures.saveJob(
                transcriptId,
                "FAILED",
                failedAt,
                failedAt
        );
    }

    private long meetingSummaryCount(long recordingId) {
        return jdbcClient.sql("SELECT COUNT(*) FROM recording_meeting_summaries WHERE recording_id=:recordingId")
                .param(
                        "recordingId",
                        recordingId
                )
                .query(Long.class)
                .single();
    }

    private Tuple listed(RecentRecordingItemResult item) {
        return tuple(
                item.recordingId(),
                item.title(),
                item.startedAt(),
                item.recordingDurationSeconds()
        );
    }

    private RecordingDocumentResult find(long recordingId) {
        return recordingDocumentService.find(
                workspaceId,
                memberId,
                recordingId
        );
    }

    private ErrorCode errorCode(long recordingId) {
        try {
            find(recordingId);
        } catch (RecordingException exception) {
            return exception.getErrorCode();
        }
        throw new AssertionError("녹음 문서 조회가 실패하지 않았다");
    }
}
