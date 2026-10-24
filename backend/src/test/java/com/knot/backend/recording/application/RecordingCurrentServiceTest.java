package com.knot.backend.recording.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.any;
import java.util.List;
import com.knot.backend.recording.domain.model.RecordingAudioUpload;
import com.knot.backend.recording.domain.repository.RecordingAudioUploadRepository;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.knot.backend.member.domain.MemberRepository;
import com.knot.backend.recording.application.dto.result.RecordingCurrentResult;
import com.knot.backend.recording.domain.model.RecordingSession;
import com.knot.backend.recording.domain.repository.RecordingSessionRepository;
import com.knot.backend.recording.application.dto.result.CurrentRecordingStatus;
import com.knot.backend.workspace.domain.model.Workspace;
import com.knot.backend.workspace.domain.WorkspaceErrorCode;
import com.knot.backend.workspace.domain.WorkspaceException;
import com.knot.backend.workspace.domain.repository.WorkspaceMemberRepository;
import com.knot.backend.workspace.domain.repository.WorkspaceRepository;
import com.knot.backend.global.config.RecordingAudioChunksProperties;
import com.knot.backend.recording.domain.repository.RecordingAudioStreamRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import com.knot.backend.document.application.dto.result.DocumentGenerationHomeResult;
import com.knot.backend.document.domain.model.DocumentGenerationProcessingStatus;
import com.knot.backend.recording.domain.model.AudioFinalizationStatus;
import com.knot.backend.recording.domain.model.RecordingAudioFinalization;
import com.knot.backend.recording.domain.model.RecordingTranscription;
import com.knot.backend.recording.domain.model.TranscriptionStatus;

class RecordingCurrentServiceTest {
    private static final Instant STARTED_AT = Instant.parse("2026-10-05T00:00:00Z");
    private static final Instant NOW = Instant.parse("2026-10-05T00:10:00.123456Z");
    private static final long WORKSPACE_ID = 10L;
    private static final long MEMBER_ID = 1L;
    private static final long RECORDING_ID = 7L;
    private static final UUID TAB_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final String HASH = "a".repeat(64);

    private WorkspaceRepository workspaceRepository;
    private WorkspaceMemberRepository workspaceMemberRepository;
    private RecordingSessionRepository recordingSessionRepository;
    private com.knot.backend.recording.domain.repository.RecordingAudioFinalizationRepository finalizations;
    private com.knot.backend.recording.domain.repository.RecordingTranscriptionRepository transcriptions;
    private com.knot.backend.document.application.DocumentGenerationProgressService documentProgress;
    private RecordingAudioUploadRepository uploads;
    private com.knot.backend.global.config.TranscriptionProperties transcriptionProperties;
    private RecordingService service;

    @BeforeEach
    void setUp() {
        workspaceRepository = mock(WorkspaceRepository.class);
        workspaceMemberRepository = mock(WorkspaceMemberRepository.class);
        recordingSessionRepository = mock(RecordingSessionRepository.class);
        finalizations = mock(com.knot.backend.recording.domain.repository.RecordingAudioFinalizationRepository.class);
        transcriptions = mock(com.knot.backend.recording.domain.repository.RecordingTranscriptionRepository.class);
        documentProgress = mock(com.knot.backend.document.application.DocumentGenerationProgressService.class);
        uploads = mock(RecordingAudioUploadRepository.class);
        transcriptionProperties = mock(com.knot.backend.global.config.TranscriptionProperties.class);
        when(transcriptionProperties.enabled()).thenReturn(true);
        service = new RecordingService(
                new RecordingAccess(
                        workspaceRepository,
                        workspaceMemberRepository,
                        recordingSessionRepository,
                        mock(RecordingControlTokenHasher.class)
                ),
                mock(MemberRepository.class),
                recordingSessionRepository,
                mock(RecordingControlTokenHasher.class),
                Clock.fixed(
                        NOW,
                        ZoneOffset.UTC
                ),
                mock(RecordingAudioSealing.class),
                mock(RecordingAudioStreamRepository.class),
                new RecordingAudioChunksProperties(false),
                mock(com.knot.backend.recording.domain.repository.RecordingAudioChunkRepository.class),
                finalizations,
                mock(com.knot.backend.recording.domain.repository.RecordingAudioCleanupRepository.class),
                transcriptions,
                documentProgress,
                uploads,
                transcriptionProperties
        );
    }

    @Test
    @DisplayName("진행 중인 본인 녹음은 서버 시각까지의 누적 시간과 함께 반환한다")
    void findCurrent_success_recording() {
        // given
        prepareAccess();
        RecordingSession session = recordingSession();
        when(
                recordingSessionRepository.findCurrentByWorkspaceIdAndMemberId(
                        WORKSPACE_ID,
                        MEMBER_ID,
                        NOW
                )
        ).thenReturn(Optional.of(session));

        // when
        Optional<RecordingCurrentResult> result = service.findCurrent(
                WORKSPACE_ID,
                MEMBER_ID
        );

        // then
        assertThat(result).contains(
                new RecordingCurrentResult(
                        RECORDING_ID,
                        CurrentRecordingStatus.RECORDING,
                        STARTED_AT,
                        600_123L
                )
        );
    }

    @Test
    @DisplayName("일시정지한 녹음은 일시정지 이후 시간을 누적 시간에 더하지 않는다")
    void findCurrent_success_pausedExcludesPausedTime() {
        // given
        prepareAccess();
        RecordingSession session = recordingSession();
        session.pause(STARTED_AT.plusSeconds(20));
        when(
                recordingSessionRepository.findCurrentByWorkspaceIdAndMemberId(
                        WORKSPACE_ID,
                        MEMBER_ID,
                        NOW
                )
        ).thenReturn(Optional.of(session));

        // when
        Optional<RecordingCurrentResult> result = service.findCurrent(
                WORKSPACE_ID,
                MEMBER_ID
        );

        // then
        assertThat(result).hasValueSatisfying(current -> {
            assertThat(current.status()).isEqualTo(CurrentRecordingStatus.PAUSED);
            assertThat(current.elapsedMillis()).isEqualTo(20_000L);
        });
    }

    @Test
    @DisplayName("현재 시각을 확인하기 전에 다른 요청이 재개를 커밋해도 읽은 녹음 기준으로 누적 시간을 계산한다")
    void findCurrent_success_resumeCommittedBeforeRead() {
        // given
        prepareAccess();
        AtomicReference<Instant> currentTime = new AtomicReference<>(NOW);
        Clock clock = mock(Clock.class);
        when(clock.instant()).thenAnswer(invocation -> currentTime.get());
        Instant resumedAt = NOW.plusMillis(2);
        RecordingSession session = recordingSession();
        session.pause(STARTED_AT.plusSeconds(20));
        keepAlive(
                session,
                NOW
        );
        when(
                recordingSessionRepository.findCurrentByWorkspaceIdAndMemberId(
                        WORKSPACE_ID,
                        MEMBER_ID,
                        NOW
                )
        ).thenAnswer(invocation -> {
            session.resume(resumedAt);
            currentTime.set(resumedAt.plusMillis(1));
            return Optional.of(session);
        });
        RecordingService racingService = new RecordingService(
                new RecordingAccess(
                        workspaceRepository,
                        workspaceMemberRepository,
                        recordingSessionRepository,
                        mock(RecordingControlTokenHasher.class)
                ),
                mock(MemberRepository.class),
                recordingSessionRepository,
                mock(RecordingControlTokenHasher.class),
                clock,
                mock(RecordingAudioSealing.class),
                mock(RecordingAudioStreamRepository.class),
                new RecordingAudioChunksProperties(false),
                mock(com.knot.backend.recording.domain.repository.RecordingAudioChunkRepository.class),
                finalizations,
                mock(com.knot.backend.recording.domain.repository.RecordingAudioCleanupRepository.class),
                transcriptions,
                documentProgress,
                uploads,
                transcriptionProperties
        );

        // when
        Optional<RecordingCurrentResult> result = racingService.findCurrent(
                WORKSPACE_ID,
                MEMBER_ID
        );

        // then
        assertThat(result).hasValueSatisfying(current -> {
            assertThat(current.status()).isEqualTo(CurrentRecordingStatus.RECORDING);
            assertThat(current.elapsedMillis()).isEqualTo(20_001L);
        });
    }

    @Test
    @DisplayName("요청 Workspace에 본인 녹음이 없으면 빈 결과를 반환한다")
    void findCurrent_success_noRecording() {
        // given
        prepareAccess();
        when(
                recordingSessionRepository.findCurrentByWorkspaceIdAndMemberId(
                        WORKSPACE_ID,
                        MEMBER_ID,
                        NOW
                )
        ).thenReturn(Optional.empty());

        // when
        Optional<RecordingCurrentResult> result = service.findCurrent(
                WORKSPACE_ID,
                MEMBER_ID
        );

        // then
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("조회는 Workspace를 잠그지 않고 녹음 세션을 저장하지 않는다")
    void findCurrent_success_readsWithoutLockOrSave() {
        // given
        prepareAccess();
        RecordingSession session = recordingSession();
        when(
                recordingSessionRepository.findCurrentByWorkspaceIdAndMemberId(
                        WORKSPACE_ID,
                        MEMBER_ID,
                        NOW
                )
        ).thenReturn(Optional.of(session));

        // when
        service.findCurrent(
                WORKSPACE_ID,
                MEMBER_ID
        );

        // then
        verify(
                workspaceRepository,
                never()
        ).findByIdForUpdate(WORKSPACE_ID);
        verify(
                recordingSessionRepository,
                never()
        ).save(session);
        assertThat(session.getLastSeenAt()).isEqualTo(STARTED_AT);
    }

    @Test
    @DisplayName("Workspace 멤버가 아니면 녹음을 조회하지 않고 거절한다")
    void findCurrent_failure_notWorkspaceMember() {
        // given
        when(workspaceRepository.findById(WORKSPACE_ID)).thenReturn(Optional.of(workspace()));
        when(
                workspaceMemberRepository.existsByWorkspaceIdAndMemberId(
                        WORKSPACE_ID,
                        MEMBER_ID
                )
        ).thenReturn(false);

        // when
        Throwable failure = catchThrowable(
                () -> service.findCurrent(
                        WORKSPACE_ID,
                        MEMBER_ID
                )
        );

        // then
        assertThat(failure).isInstanceOf(WorkspaceException.class)
                .extracting("errorCode")
                .isEqualTo(WorkspaceErrorCode.WORKSPACE_ACCESS_DENIED);
        verifyNoInteractions(recordingSessionRepository);
    }

    @Test
    @DisplayName("없거나 삭제된 Workspace면 WORKSPACE_NOT_FOUND로 거절한다")
    void findCurrent_failure_workspaceNotFound() {
        // given
        when(workspaceRepository.findById(WORKSPACE_ID)).thenReturn(Optional.empty());

        // when
        Throwable failure = catchThrowable(
                () -> service.findCurrent(
                        WORKSPACE_ID,
                        MEMBER_ID
                )
        );

        // then
        assertThat(failure).isInstanceOf(WorkspaceException.class)
                .extracting("errorCode")
                .isEqualTo(WorkspaceErrorCode.WORKSPACE_NOT_FOUND);
        verifyNoInteractions(recordingSessionRepository);
    }

    @Test
    @DisplayName("0 이하 Workspace ID는 저장소를 조회하지 않고 INVALID_WORKSPACE_ID로 거절한다")
    void findCurrent_failure_invalidWorkspaceId() {
        // given
        long invalidWorkspaceId = 0L;

        // when
        Throwable failure = catchThrowable(
                () -> service.findCurrent(
                        invalidWorkspaceId,
                        MEMBER_ID
                )
        );

        // then
        assertThat(failure).isInstanceOf(WorkspaceException.class)
                .extracting("errorCode")
                .isEqualTo(WorkspaceErrorCode.INVALID_WORKSPACE_ID);
        verifyNoInteractions(workspaceRepository);
        verify(
                recordingSessionRepository,
                never()
        ).findCurrentByWorkspaceIdAndMemberId(
                anyLong(),
                anyLong(),
                any(Instant.class)
        );
    }

    @Test
    @DisplayName("종료 직후 업로드와 처리 결과가 없으면 문서 생성 중으로 반환한다")
    void findCurrent_success_waitingForAudio() {
        // given
        prepareEndedRecording();

        // when
        Optional<RecordingCurrentResult> result = service.findCurrent(
                WORKSPACE_ID,
                MEMBER_ID
        );

        // then
        assertThat(result).hasValueSatisfying(current -> {
            assertThat(current.status()).isEqualTo(CurrentRecordingStatus.PROCESSING);
            assertThat(current.elapsedMillis()).isEqualTo(20_000L);
        });
    }

    @ParameterizedTest
    @EnumSource(value = DocumentGenerationProcessingStatus.class, names = {"QUEUED", "RUNNING"})
    @DisplayName("문서 생성 배치가 대기 또는 실행 중이면 문서 생성 중으로 반환한다")
    void findCurrent_success_documentGenerationInProgress(DocumentGenerationProcessingStatus status) {
        // given
        prepareEndedRecording();
        when(
                documentProgress.findHomeProgress(
                        WORKSPACE_ID,
                        RECORDING_ID,
                        NOW
                )
        ).thenReturn(Optional.of(progress(status)));

        // when
        Optional<RecordingCurrentResult> result = service.findCurrent(
                WORKSPACE_ID,
                MEMBER_ID
        );

        // then
        assertThat(result).hasValueSatisfying(
                current -> assertThat(current.status()).isEqualTo(CurrentRecordingStatus.PROCESSING)
        );
    }

    @ParameterizedTest
    @EnumSource(value = DocumentGenerationProcessingStatus.class, names = {"SUCCEEDED", "FAILED", "NO_CONTENT"})
    @DisplayName("문서 생성 배치가 최종 상태면 최신 녹음을 표시하지 않는다")
    void findCurrent_success_terminalDocumentGeneration(DocumentGenerationProcessingStatus status) {
        // given
        prepareEndedRecording();
        when(
                documentProgress.findHomeProgress(
                        WORKSPACE_ID,
                        RECORDING_ID,
                        NOW
                )
        ).thenReturn(Optional.of(progress(status)));

        // when
        Optional<RecordingCurrentResult> result = service.findCurrent(
                WORKSPACE_ID,
                MEMBER_ID
        );

        // then
        assertThat(result).isEmpty();
        verify(
                recordingSessionRepository,
                never()
        ).findActiveByWorkspaceIdAndMemberId(
                anyLong(),
                anyLong()
        );
    }

    @ParameterizedTest
    @EnumSource(value = TranscriptionStatus.class, names = {"QUEUED", "PROCESSING", "SUCCEEDED"})
    @DisplayName("문서 배치 없이 전사 작업이 진행되거나 배치 생성을 기다리면 문서 생성 중이다")
    void findCurrent_success_transcriptionInProgress(TranscriptionStatus status) {
        // given
        prepareEndedRecording();
        RecordingTranscription transcription = mock(RecordingTranscription.class);
        when(transcription.getStatus()).thenReturn(status);
        when(transcriptions.findByRecordingSessionId(RECORDING_ID)).thenReturn(Optional.of(transcription));

        // when
        Optional<RecordingCurrentResult> result = service.findCurrent(
                WORKSPACE_ID,
                MEMBER_ID
        );

        // then
        assertThat(result).hasValueSatisfying(
                current -> assertThat(current.status()).isEqualTo(CurrentRecordingStatus.PROCESSING)
        );
    }

    @Test
    @DisplayName("전사가 실패하면 파일을 쓸 수 없어도 실패 상태와 재시도 불가를 반환한다")
    void findCurrent_success_failedTranscriptionVisible() {
        // given
        prepareEndedRecording();
        RecordingTranscription transcription = mock(RecordingTranscription.class);
        when(transcription.getStatus()).thenReturn(TranscriptionStatus.FAILED);
        when(transcriptions.findByRecordingSessionId(RECORDING_ID)).thenReturn(Optional.of(transcription));

        // when
        Optional<RecordingCurrentResult> result = service.findCurrent(
                WORKSPACE_ID,
                MEMBER_ID
        );

        // then
        assertThat(result).hasValueSatisfying(current -> {
            assertThat(current.status()).isEqualTo(CurrentRecordingStatus.TRANSCRIPTION_FAILED);
            assertThat(current.retryable()).isFalse();
            assertThat(current.retryableJobIds()).isEmpty();
        });
    }

    @ParameterizedTest
    @EnumSource(value = AudioFinalizationStatus.class, names = {"QUEUED", "PROCESSING", "READY"})
    @DisplayName("오디오 준비 또는 전사 대기 중인 최신 녹음은 문서 생성 중으로 반환한다")
    void findCurrent_success_audioInProgress(AudioFinalizationStatus status) {
        // given
        prepareEndedRecording();
        RecordingAudioFinalization finalization = mock(RecordingAudioFinalization.class);
        when(finalization.getStatus()).thenReturn(status);
        when(finalizations.findByRecordingId(RECORDING_ID)).thenReturn(Optional.of(finalization));

        // when
        Optional<RecordingCurrentResult> result = service.findCurrent(
                WORKSPACE_ID,
                MEMBER_ID
        );

        // then
        assertThat(result).hasValueSatisfying(
                current -> assertThat(current.status()).isEqualTo(CurrentRecordingStatus.PROCESSING)
        );
    }

    @ParameterizedTest
    @EnumSource(value = AudioFinalizationStatus.class, names = {"FAILED", "DISCARDED"})
    @DisplayName("오디오 준비가 실패하거나 폐기되면 문서 생성 중으로 표시하지 않는다")
    void findCurrent_success_terminalAudioExcluded(AudioFinalizationStatus status) {
        // given
        prepareEndedRecording();
        RecordingAudioFinalization finalization = mock(RecordingAudioFinalization.class);
        when(finalization.getStatus()).thenReturn(status);
        when(finalizations.findByRecordingId(RECORDING_ID)).thenReturn(Optional.of(finalization));

        // when
        Optional<RecordingCurrentResult> result = service.findCurrent(
                WORKSPACE_ID,
                MEMBER_ID
        );

        // then
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("폐기된 최신 녹음은 표시하지 않고 처리 상태도 조회하지 않는다")
    void findCurrent_success_discardedExcluded() {
        // given
        prepareAccess();
        RecordingSession session = recordingSession();
        session.discard(STARTED_AT.plusSeconds(20));
        when(
                recordingSessionRepository.findCurrentByWorkspaceIdAndMemberId(
                        WORKSPACE_ID,
                        MEMBER_ID,
                        NOW
                )
        ).thenReturn(Optional.of(session));

        // when
        Optional<RecordingCurrentResult> result = service.findCurrent(
                WORKSPACE_ID,
                MEMBER_ID
        );

        // then
        assertThat(result).isEmpty();
        verifyNoInteractions(
                transcriptions,
                finalizations,
                documentProgress
        );
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"true,true,true", "true,false,false", "false,true,false"})
    @DisplayName("전사 재접수는 기능 활성화와 사용 가능한 오디오가 모두 필요하다")
    void findCurrent_success_retryableTranscription(
            boolean enabled,
            boolean available,
            boolean expected
    ) {
        prepareEndedRecording();
        RecordingTranscription transcription = mock(RecordingTranscription.class);
        when(transcription.getStatus()).thenReturn(TranscriptionStatus.FAILED);
        when(transcriptions.findByRecordingSessionId(RECORDING_ID)).thenReturn(Optional.of(transcription));
        Instant old = NOW.minus(java.time.Duration.ofDays(365));
        RecordingAudioUpload upload = RecordingAudioUpload.reserve(
                RECORDING_ID,
                "recordings/old.webm",
                "audio/webm",
                100,
                old
        );
        upload.complete(
                com.knot.backend.recording.domain.model.StoredAudioObject.of(
                        100,
                        "audio/webm",
                        "old-v1"
                ),
                old.plusSeconds(60)
        );
        if (!available)
            upload.recordDeleted(NOW);
        when(transcriptionProperties.enabled()).thenReturn(enabled);
        when(uploads.findByRecordingId(RECORDING_ID)).thenReturn(Optional.of(upload));

        assertThat(
                service.findCurrent(
                        WORKSPACE_ID,
                        MEMBER_ID
                )
        ).hasValueSatisfying(current -> {
            assertThat(current.status()).isEqualTo(CurrentRecordingStatus.TRANSCRIPTION_FAILED);
            assertThat(current.retryable()).isEqualTo(expected);
            assertThat(current.retryableJobIds()).isEmpty();
        });
    }

    @Test
    @DisplayName("문서 실패는 실제 접수 가능한 작업 ID와 일치하는 재시도 여부를 반환한다")
    void findCurrent_success_retryableDocumentJobs() {
        prepareEndedRecording();
        when(
                documentProgress.findHomeProgress(
                        WORKSPACE_ID,
                        RECORDING_ID,
                        NOW
                )
        ).thenReturn(
                Optional.of(
                        new DocumentGenerationHomeResult(
                                DocumentGenerationProcessingStatus.FAILED,
                                true,
                                List.of(
                                        88L,
                                        91L
                                )
                        )
                )
        );

        assertThat(
                service.findCurrent(
                        WORKSPACE_ID,
                        MEMBER_ID
                )
        ).hasValueSatisfying(current -> {
            assertThat(current.status()).isEqualTo(CurrentRecordingStatus.DOCUMENT_FAILED);
            assertThat(current.retryable()).isTrue();
            assertThat(current.retryableJobIds()).containsExactly(
                    88L,
                    91L
            );
        });
    }

    @Test
    @DisplayName("표시 기한 안의 문서 실패는 접수 가능한 작업이 없어도 제목을 유지한다")
    void findCurrent_success_nonRetryableDocumentFailure() {
        prepareEndedRecording();
        when(
                documentProgress.findHomeProgress(
                        WORKSPACE_ID,
                        RECORDING_ID,
                        NOW
                )
        ).thenReturn(
                Optional.of(
                        new DocumentGenerationHomeResult(
                                DocumentGenerationProcessingStatus.FAILED,
                                true,
                                List.of()
                        )
                )
        );

        assertThat(
                service.findCurrent(
                        WORKSPACE_ID,
                        MEMBER_ID
                )
        ).hasValueSatisfying(current -> {
            assertThat(current.status()).isEqualTo(CurrentRecordingStatus.DOCUMENT_FAILED);
            assertThat(current.retryable()).isFalse();
            assertThat(current.retryableJobIds()).isEmpty();
        });
    }

    private void prepareEndedRecording() {
        prepareAccess();
        RecordingSession session = recordingSession();
        session.end(STARTED_AT.plusSeconds(20));
        when(
                recordingSessionRepository.findCurrentByWorkspaceIdAndMemberId(
                        WORKSPACE_ID,
                        MEMBER_ID,
                        NOW
                )
        ).thenReturn(Optional.of(session));
    }

    private DocumentGenerationHomeResult progress(DocumentGenerationProcessingStatus status) {
        return new DocumentGenerationHomeResult(
                status,
                false,
                List.of()
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

    private RecordingSession recordingSession() {
        RecordingSession session = spy(
                RecordingSession.start(
                        WORKSPACE_ID,
                        MEMBER_ID,
                        UUID.randomUUID(),
                        TAB_ID,
                        HASH,
                        STARTED_AT
                )
        );
        doReturn(RECORDING_ID).when(session)
                .getId();
        return session;
    }

    private void keepAlive(
            RecordingSession session,
            Instant until
    ) {
        for (Instant at = session.getLastSeenAt()
                .plusSeconds(10); at.isBefore(until); at = at.plusSeconds(10)) {
            session.recordHeartbeat(at);
        }
    }
}
