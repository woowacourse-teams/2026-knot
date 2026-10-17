package com.knot.backend.recording.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import com.knot.backend.recording.application.dto.result.RecordingDetailResult;
import com.knot.backend.recording.application.dto.result.RecordingDetailSnapshot;
import com.knot.backend.recording.application.dto.result.RecordingTranscriptionSnapshot;
import com.knot.backend.recording.domain.model.TranscriptionStatus;
import com.knot.backend.document.application.DocumentGenerationProgressService;
import com.knot.backend.document.application.dto.result.DocumentGenerationSummaryResult;
import com.knot.backend.document.domain.model.DocumentGenerationProcessingStatus;
import java.util.List;
import java.util.UUID;
import com.knot.backend.recording.domain.model.RecordingAudioUploadStatus;
import com.knot.backend.recording.domain.model.RecordingEndReason;
import com.knot.backend.recording.domain.RecordingErrorCode;
import com.knot.backend.recording.domain.RecordingException;
import com.knot.backend.recording.domain.repository.RecordingSessionRepository;
import com.knot.backend.recording.domain.model.RecordingStatus;
import com.knot.backend.workspace.domain.model.Workspace;
import com.knot.backend.workspace.domain.WorkspaceErrorCode;
import com.knot.backend.workspace.domain.WorkspaceException;
import com.knot.backend.workspace.domain.repository.WorkspaceMemberRepository;
import com.knot.backend.workspace.domain.repository.WorkspaceRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RecordingDetailServiceTest {
    private static final Instant STARTED_AT = Instant.parse("2026-10-09T00:00:00Z");
    private static final Instant NOW = Instant.parse("2026-10-09T00:01:30.123456Z");
    private static final long WORKSPACE_ID = 10L;
    private static final long MEMBER_ID = 1L;
    private static final long RECORDING_ID = 7L;

    private WorkspaceRepository workspaceRepository;
    private WorkspaceMemberRepository workspaceMemberRepository;
    private RecordingDetailQuery recordingDetailQuery;
    private RecordingTranscriptionStateService recordingTranscriptionStateService;
    private DocumentGenerationProgressService documentGenerationProgressService;
    private RecordingDetailService service;

    @BeforeEach
    void setUp() {
        workspaceRepository = mock(WorkspaceRepository.class);
        workspaceMemberRepository = mock(WorkspaceMemberRepository.class);
        recordingDetailQuery = mock(RecordingDetailQuery.class);
        recordingTranscriptionStateService = mock(RecordingTranscriptionStateService.class);
        documentGenerationProgressService = mock(DocumentGenerationProgressService.class);
        service = new RecordingDetailService(
                new RecordingAccess(
                        workspaceRepository,
                        workspaceMemberRepository,
                        mock(RecordingSessionRepository.class),
                        mock(RecordingControlTokenHasher.class)
                ),
                recordingDetailQuery,
                recordingTranscriptionStateService,
                documentGenerationProgressService,
                Clock.fixed(
                        NOW,
                        ZoneOffset.UTC
                ),
                org.mockito.Mockito.mock(com.knot.backend.recording.application.RecordingAudioFinalizationQuery.class)
        );
    }

    @Test
    @DisplayName("진행 중인 본인 녹음은 서버 시각까지의 경과 시간을 반환한다")
    void find_success_recording() {
        // given
        prepareAccess();
        when(
                recordingDetailQuery.find(
                        WORKSPACE_ID,
                        RECORDING_ID
                )
        ).thenReturn(
                Optional.of(
                        snapshot(
                                RecordingStatus.RECORDING,
                                NOW.minusSeconds(30),
                                null,
                                null
                        )
                )
        );

        // when
        RecordingDetailResult result = service.find(
                WORKSPACE_ID,
                MEMBER_ID,
                RECORDING_ID
        );

        // then
        assertThat(result.status()).isEqualTo(RecordingStatus.RECORDING);
        assertThat(result.elapsedMillis()).isEqualTo(90_000L);
        assertThat(result.audioUploadStatus()).isNull();
        assertThat(result.transcriptionAttemptId()).isNull();
        assertThat(result.transcriptionStatus()).isNull();
        assertThat(result.transcriptionFailureCode()).isNull();
        assertThat(result.documentGeneration()).isNull();
    }

    @Test
    @DisplayName("종료되고 업로드가 끝난 녹음은 종료 시각·사유와 업로드 완료 상태를 반환한다")
    void find_success_endedWithCompletedUpload() {
        // given
        prepareAccess();
        Instant endedAt = STARTED_AT.plusSeconds(60);
        when(
                recordingDetailQuery.find(
                        WORKSPACE_ID,
                        RECORDING_ID
                )
        ).thenReturn(
                Optional.of(
                        snapshot(
                                RecordingStatus.ENDED,
                                null,
                                endedAt,
                                RecordingAudioUploadStatus.COMPLETED
                        )
                )
        );

        UUID attemptId = UUID.randomUUID();
        when(recordingTranscriptionStateService.findSnapshot(RECORDING_ID)).thenReturn(
                new RecordingTranscriptionSnapshot(
                        attemptId,
                        TranscriptionStatus.FAILED,
                        "INFERENCE_FAILED"
                )
        );

        // when
        RecordingDetailResult result = service.find(
                WORKSPACE_ID,
                MEMBER_ID,
                RECORDING_ID
        );

        // then
        assertThat(result.status()).isEqualTo(RecordingStatus.ENDED);
        assertThat(result.endedAt()).isEqualTo(endedAt);
        assertThat(result.endReason()).isEqualTo(RecordingEndReason.USER_ENDED);
        assertThat(result.elapsedMillis()).isEqualTo(60_000L);
        assertThat(result.audioUploadStatus()).isEqualTo(RecordingAudioUploadStatus.COMPLETED);
        assertThat(result.transcriptionAttemptId()).isEqualTo(attemptId);
        assertThat(result.transcriptionStatus()).isEqualTo(TranscriptionStatus.FAILED);
        assertThat(result.transcriptionFailureCode()).isEqualTo("INFERENCE_FAILED");
    }

    @Test
    @DisplayName("진행 구간 시작이 서버 시각보다 늦게 기록돼도 경과 시간을 음수로 세지 않는다")
    void find_success_doesNotCountNegativeInterval() {
        // given
        prepareAccess();
        when(
                recordingDetailQuery.find(
                        WORKSPACE_ID,
                        RECORDING_ID
                )
        ).thenReturn(
                Optional.of(
                        snapshot(
                                RecordingStatus.RECORDING,
                                NOW.plusMillis(5),
                                null,
                                null
                        )
                )
        );

        // when
        RecordingDetailResult result = service.find(
                WORKSPACE_ID,
                MEMBER_ID,
                RECORDING_ID
        );

        // then
        assertThat(result.elapsedMillis()).isEqualTo(60_000L);
    }

    @Test
    @DisplayName("상한 회수 전에 조회해도 진행 중인 녹음의 경과 시간은 2시간을 넘지 않는다")
    void find_success_capsElapsedAtMaxDuration() {
        // given
        prepareAccess();
        when(
                recordingDetailQuery.find(
                        WORKSPACE_ID,
                        RECORDING_ID
                )
        ).thenReturn(
                Optional.of(
                        snapshot(
                                RecordingStatus.RECORDING,
                                NOW.minusSeconds(7_200),
                                null,
                                null
                        )
                )
        );

        // when
        RecordingDetailResult result = service.find(
                WORKSPACE_ID,
                MEMBER_ID,
                RECORDING_ID
        );

        // then
        assertThat(result.elapsedMillis()).isEqualTo(7_200_000L);
    }

    @Test
    @DisplayName("다른 회원의 녹음은 시작자가 아니라는 이유로 거절한다")
    void find_failure_otherMember() {
        // given
        prepareAccess();
        when(
                recordingDetailQuery.find(
                        WORKSPACE_ID,
                        RECORDING_ID
                )
        ).thenReturn(
                Optional.of(
                        new RecordingDetailSnapshot(
                                MEMBER_ID + 1,
                                RecordingStatus.RECORDING,
                                STARTED_AT,
                                0L,
                                null,
                                null,
                                null
                        )
                )
        );

        // when
        Throwable thrown = catchThrowable(
                () -> service.find(
                        WORKSPACE_ID,
                        MEMBER_ID,
                        RECORDING_ID
                )
        );

        // then
        assertThat(thrown).isInstanceOf(RecordingException.class)
                .extracting("errorCode")
                .isEqualTo(RecordingErrorCode.RECORDING_CONTROL_DENIED);
        verifyNoInteractions(
                documentGenerationProgressService,
                recordingTranscriptionStateService
        );
    }

    @Test
    @DisplayName("요청한 Workspace에 없는 녹음은 녹음 없음으로 거절한다")
    void find_failure_notFound() {
        // given
        prepareAccess();
        when(
                recordingDetailQuery.find(
                        WORKSPACE_ID,
                        RECORDING_ID
                )
        ).thenReturn(Optional.empty());

        // when
        Throwable thrown = catchThrowable(
                () -> service.find(
                        WORKSPACE_ID,
                        MEMBER_ID,
                        RECORDING_ID
                )
        );

        // then
        assertThat(thrown).isInstanceOf(RecordingException.class)
                .extracting("errorCode")
                .isEqualTo(RecordingErrorCode.RECORDING_NOT_FOUND);
    }

    @Test
    @DisplayName("Workspace 멤버가 아니면 녹음을 조회하지 않고 거절한다")
    void find_failure_notWorkspaceMember() {
        // given
        when(workspaceRepository.findById(WORKSPACE_ID)).thenReturn(Optional.of(workspace()));
        when(
                workspaceMemberRepository.existsByWorkspaceIdAndMemberId(
                        WORKSPACE_ID,
                        MEMBER_ID
                )
        ).thenReturn(false);

        // when
        Throwable thrown = catchThrowable(
                () -> service.find(
                        WORKSPACE_ID,
                        MEMBER_ID,
                        RECORDING_ID
                )
        );

        // then
        assertThat(thrown).isInstanceOf(WorkspaceException.class)
                .extracting("errorCode")
                .isEqualTo(WorkspaceErrorCode.WORKSPACE_ACCESS_DENIED);
        verifyNoInteractions(
                recordingDetailQuery,
                documentGenerationProgressService,
                recordingTranscriptionStateService
        );
    }

    @Test
    @DisplayName("양수가 아닌 녹음 ID는 녹음을 조회하지 않고 거절한다")
    void find_failure_invalidRecordingId() {
        // given
        prepareAccess();

        // when
        Throwable thrown = catchThrowable(
                () -> service.find(
                        WORKSPACE_ID,
                        MEMBER_ID,
                        0L
                )
        );

        // then
        assertThat(thrown).isInstanceOf(RecordingException.class)
                .extracting("errorCode")
                .isEqualTo(RecordingErrorCode.INVALID_RECORDING_DATA);
        verifyNoInteractions(
                recordingDetailQuery,
                documentGenerationProgressService,
                recordingTranscriptionStateService
        );
    }

    @Test
    @DisplayName("문서 생성 집계와 실패 작업 ID를 본인 녹음 응답에 함께 반환한다")
    void find_success_documentGeneration() {
        // given
        prepareAccess();
        when(
                recordingDetailQuery.find(
                        WORKSPACE_ID,
                        RECORDING_ID
                )
        ).thenReturn(
                Optional.of(
                        snapshot(
                                RecordingStatus.ENDED,
                                null,
                                STARTED_AT.plusSeconds(60),
                                RecordingAudioUploadStatus.COMPLETED
                        )
                )
        );
        DocumentGenerationSummaryResult summary = new DocumentGenerationSummaryResult(
                DocumentGenerationProcessingStatus.FAILED,
                2,
                List.of(104L)
        );
        when(
                documentGenerationProgressService.findSummary(
                        WORKSPACE_ID,
                        RECORDING_ID,
                        NOW
                )
        ).thenReturn(Optional.of(summary));

        // when
        RecordingDetailResult result = service.find(
                WORKSPACE_ID,
                MEMBER_ID,
                RECORDING_ID
        );

        // then
        assertThat(result.documentGeneration()).isEqualTo(summary);
        assertThat(result.status()).isEqualTo(RecordingStatus.ENDED);
    }

    private RecordingDetailSnapshot snapshot(
            RecordingStatus status,
            Instant currentIntervalStartedAt,
            Instant endedAt,
            RecordingAudioUploadStatus uploadStatus
    ) {
        return new RecordingDetailSnapshot(
                MEMBER_ID,
                status,
                currentIntervalStartedAt,
                60_000L,
                endedAt,
                endedAt == null ? null : RecordingEndReason.USER_ENDED,
                uploadStatus
        );
    }

    private void prepareAccess() {
        when(workspaceRepository.findById(WORKSPACE_ID)).thenReturn(Optional.of(workspace()));
        when(
                workspaceMemberRepository.existsByWorkspaceIdAndMemberId(
                        WORKSPACE_ID,
                        MEMBER_ID
                )
        ).thenReturn(true);
    }

    private Workspace workspace() {
        return Workspace.create(
                "Knot 팀",
                STARTED_AT
        );
    }
}
