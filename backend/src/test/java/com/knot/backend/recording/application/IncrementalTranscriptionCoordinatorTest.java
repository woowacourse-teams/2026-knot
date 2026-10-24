package com.knot.backend.recording.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.knot.backend.recording.application.dto.result.AiTranscript;
import com.knot.backend.recording.domain.RecordingException;
import com.knot.backend.recording.domain.RecordingErrorCode;
import com.knot.backend.recording.domain.model.AudioFinalizationStatus;
import com.knot.backend.recording.domain.model.AudioManifestPart;
import com.knot.backend.recording.domain.model.RecordingAudioFinalization;
import com.knot.backend.recording.domain.model.RecordingAudioMode;
import com.knot.backend.recording.domain.model.RecordingAudioStream;
import com.knot.backend.recording.domain.model.RecordingAudioUpload;
import com.knot.backend.recording.domain.model.RecordingSession;
import com.knot.backend.recording.domain.model.RecordingStatus;
import com.knot.backend.recording.domain.model.RecordingTranscriptionWindow;
import com.knot.backend.recording.domain.model.TranscriptionWindowStatus;
import com.knot.backend.recording.domain.repository.RecordingAudioCleanupRepository;
import com.knot.backend.recording.domain.repository.RecordingAudioFinalizationRepository;
import com.knot.backend.recording.domain.repository.RecordingAudioStreamRepository;
import com.knot.backend.recording.domain.repository.RecordingAudioUploadRepository;
import com.knot.backend.recording.domain.repository.RecordingSessionRepository;
import com.knot.backend.recording.domain.repository.RecordingTranscriptionWindowRepository;
import com.knot.backend.workspace.domain.model.Workspace;
import com.knot.backend.workspace.domain.repository.WorkspaceMemberRepository;
import com.knot.backend.workspace.domain.repository.WorkspaceRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;

class IncrementalTranscriptionCoordinatorTest {
    private static final Instant NOW = Instant.parse("2026-10-18T00:00:00Z");

    private final com.knot.backend.recording.domain.repository.RecordingTranscriptionInputRepository inputs = mock(
            com.knot.backend.recording.domain.repository.RecordingTranscriptionInputRepository.class
    );
    private final RecordingSessionRepository sessions = mock(RecordingSessionRepository.class);
    private final RecordingAudioStreamRepository streams = mock(RecordingAudioStreamRepository.class);
    private final RecordingAudioFinalizationRepository finalizations = mock(RecordingAudioFinalizationRepository.class);
    private final RecordingAudioUploadRepository uploads = mock(RecordingAudioUploadRepository.class);
    private final RecordingAudioCleanupRepository cleanup = mock(RecordingAudioCleanupRepository.class);
    private final RecordingTranscriptionWindowRepository windows = mock(RecordingTranscriptionWindowRepository.class);
    private final WorkspaceRepository workspaces = mock(WorkspaceRepository.class);
    private final WorkspaceMemberRepository members = mock(WorkspaceMemberRepository.class);
    private final RecordingTranscriptionWindowService windowService = mock(RecordingTranscriptionWindowService.class);
    private final IncrementalTranscriptMerger merger = mock(IncrementalTranscriptMerger.class);
    private final RecordingTranscriptionStateService state = mock(RecordingTranscriptionStateService.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final RecordingSession session = mock(RecordingSession.class);
    private final RecordingAudioStream stream = mock(RecordingAudioStream.class);
    private final RecordingAudioFinalization finalization = mock(RecordingAudioFinalization.class);
    private final IncrementalTranscriptionCoordinator coordinator = new IncrementalTranscriptionCoordinator(
            inputs,
            sessions,
            streams,
            finalizations,
            uploads,
            cleanup,
            windows,
            workspaces,
            members,
            windowService,
            new RecordingTranscriptionWindowPlanner(mapper),
            merger,
            state,
            mapper,
            Clock.fixed(
                    NOW,
                    ZoneOffset.UTC
            )
    );

    @BeforeEach
    void setUp() {
        when(sessions.findWorkspaceIdById(1)).thenReturn(Optional.of(2L));
        when(workspaces.findIncludingDeletedByIdForUpdate(2L)).thenReturn(Optional.of(mock(Workspace.class)));
        when(workspaces.findById(2L)).thenReturn(Optional.of(mock(Workspace.class)));
        when(
                members.existsByWorkspaceIdAndMemberId(
                        2L,
                        3L
                )
        ).thenReturn(true);
        when(sessions.findByIdForUpdate(1)).thenReturn(Optional.of(session));
        when(session.getId()).thenReturn(1L);
        when(session.getWorkspaceId()).thenReturn(2L);
        when(session.getMemberId()).thenReturn(3L);
        when(session.getAudioMode()).thenReturn(RecordingAudioMode.PCM16_CHUNKS_V1);
        when(session.getIncrementalPipelineVersion()).thenReturn("v1");
        when(session.getStatus()).thenReturn(RecordingStatus.ENDED);
        when(session.getEndedAt()).thenReturn(NOW);
        when(streams.findByRecordingIdForUpdate(1)).thenReturn(Optional.of(stream));
        when(stream.getTotalFrameCount()).thenReturn(16_000L);
        when(finalizations.findByRecordingIdForUpdate(1)).thenReturn(Optional.of(finalization));
        when(finalization.getStatus()).thenReturn(AudioFinalizationStatus.READY);
        String checksum = Base64.getEncoder()
                .encodeToString(new byte[32]);
        when(finalization.getManifestJson()).thenReturn(
                mapper.writeValueAsString(
                        List.of(
                                new AudioManifestPart(
                                        1,
                                        0,
                                        16_000,
                                        "recordings/1/chunks/1.pcm",
                                        checksum,
                                        "v1"
                                )
                        )
                )
        );
        when(
                windows.findByRecordingIdAndPipelineVersionOrderByWindowIndex(
                        1,
                        "v1"
                )
        ).thenReturn(List.of());
    }

    @Test
    void endedRecordingStillPlansWindowsAfterOneYear() {
        when(session.getEndedAt()).thenReturn(NOW.minus(java.time.Duration.ofDays(365)));
        coordinator.advance(1);
        verify(windows).save(any(RecordingTranscriptionWindow.class));
    }

    @Test
    @DisplayName("활성 녹음은 30초 core와 5초 문맥으로 새 구간을 등록한다")
    void advance_success_activeRegistration() {
        // given
        when(session.isActive()).thenReturn(true);

        // when
        coordinator.advance(1);

        // then
        verify(windowService).registerAvailable(
                1,
                "v1",
                480_000,
                80_000
        );
        verifyNoInteractions(
                merger,
                state
        );
    }

    @Test
    @DisplayName("incremental로 시작하지 않은 기존 녹음은 처리하지 않는다")
    void advance_success_legacyUnaffected() {
        // given
        when(session.getIncrementalPipelineVersion()).thenReturn(null);

        // when
        coordinator.advance(1);

        // then
        verifyNoInteractions(
                windowService,
                merger,
                state
        );
    }

    @Test
    @DisplayName("오디오 권한이 취소된 녹음은 새 구간과 결과를 만들지 않는다")
    void advance_success_revokedRecordingIgnored() {
        // given
        RecordingAudioUpload upload = mock(RecordingAudioUpload.class);
        when(upload.getRevokedAt()).thenReturn(NOW);
        when(uploads.findByRecordingIdForUpdate(1)).thenReturn(Optional.of(upload));

        // when
        coordinator.advance(1);

        // then
        verify(
                windows,
                never()
        ).save(any());
        verifyNoInteractions(
                windowService,
                merger,
                state
        );
    }

    @Test
    @DisplayName("최종 WAV 저장 전에는 tail 계획과 병합을 시작하지 않는다")
    void advance_success_waitsForFinalization() {
        // given
        when(finalization.getStatus()).thenReturn(AudioFinalizationStatus.PROCESSING);

        // when
        coordinator.advance(1);

        // then
        verify(
                windows,
                never()
        ).save(any());
        verifyNoInteractions(
                merger,
                state
        );
    }

    @Test
    @DisplayName("최종 WAV 저장이 끝나면 짧은 tail 구간을 등록한다")
    void advance_success_registersFinalTail() {
        // given
        ArgumentCaptor<RecordingTranscriptionWindow> captured = ArgumentCaptor
                .forClass(RecordingTranscriptionWindow.class);

        // when
        coordinator.advance(1);

        // then
        verify(windows).save(captured.capture());
        assertThat(
                captured.getValue()
                        .getCoreEndFrame()
        ).isEqualTo(16_000);
        assertThat(
                captured.getValue()
                        .getInputEndFrame()
        ).isEqualTo(16_000);
        verifyNoInteractions(
                merger,
                state
        );
    }

    @Test
    @DisplayName("모든 구간이 성공하면 병합 결과로 공개 전사를 완료한다")
    void advance_success_completesAfterAllSucceeded() {
        // given
        RecordingTranscriptionWindow completed = existingWindow(TranscriptionWindowStatus.SUCCEEDED);
        when(
                windows.findByRecordingIdAndPipelineVersionOrderByWindowIndex(
                        1,
                        "v1"
                )
        ).thenReturn(List.of(completed));
        AiTranscript transcript = new AiTranscript(
                1,
                "{\"transcription\":[]}",
                List.of()
        );
        when(
                merger.merge(
                        List.of(completed),
                        16_000,
                        .65
                )
        ).thenReturn(transcript);

        // when
        coordinator.advance(1);

        // then
        verify(state).completeIncremental(
                1,
                transcript
        );
        verify(windows).findByIdForUpdate(10);
    }

    @Test
    @DisplayName("실패한 구간이 있으면 공개 전사를 실패로 전환한다")
    void advance_failure_failedWindow() {
        // given
        RecordingTranscriptionWindow failed = existingWindow(TranscriptionWindowStatus.FAILED);
        when(
                windows.findByRecordingIdAndPipelineVersionOrderByWindowIndex(
                        1,
                        "v1"
                )
        ).thenReturn(List.of(failed));

        // when
        coordinator.advance(1);

        // then
        verify(state).failIncremental(
                1,
                "INCREMENTAL_WINDOW_FAILED"
        );
        verifyNoInteractions(merger);
    }

    @Test
    @DisplayName("기존 구간 입력 hash가 종료 manifest와 다르면 덮어쓰지 않는다")
    void advance_failure_existingInputMismatch() {
        // given
        RecordingTranscriptionWindow existing = mock(RecordingTranscriptionWindow.class);
        when(
                windows.findByRecordingIdAndPipelineVersionAndWindowIndex(
                        1,
                        "v1",
                        0
                )
        ).thenReturn(Optional.of(existing));

        // when
        Throwable failure = catchThrowable(() -> coordinator.advance(1));

        // then
        assertThat(failure).isInstanceOf(RecordingException.class);
        verify(
                windows,
                never()
        ).save(any());
        verifyNoInteractions(
                merger,
                state
        );
    }

    @Test
    @DisplayName("병합 근거가 호환되지 않으면 공개 전사를 실패로 전환한다")
    void advance_failure_invalidMergeEvidence() {
        // given
        RecordingTranscriptionWindow completed = existingWindow(TranscriptionWindowStatus.SUCCEEDED);
        when(
                windows.findByRecordingIdAndPipelineVersionOrderByWindowIndex(
                        1,
                        "v1"
                )
        ).thenReturn(List.of(completed));
        when(
                merger.merge(
                        List.of(completed),
                        16_000,
                        .65
                )
        ).thenThrow(new RecordingException(RecordingErrorCode.INVALID_RECORDING_DATA));

        // when
        coordinator.advance(1);

        // then
        verify(state).failIncremental(
                1,
                "INCREMENTAL_MERGE_FAILED"
        );
        verify(
                state,
                never()
        ).completeIncremental(
                eq(1L),
                any()
        );
    }

    private RecordingTranscriptionWindow existingWindow(TranscriptionWindowStatus status) {
        RecordingTranscriptionWindow window = mock(RecordingTranscriptionWindow.class);
        when(window.getId()).thenReturn(10L);
        when(window.getStatus()).thenReturn(status);
        return window;
    }
}
