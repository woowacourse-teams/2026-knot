package com.knot.backend.recording.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.knot.backend.global.config.TranscriptionProperties;
import com.knot.backend.recording.application.dto.command.RecordingAudioUploadUrlCommand;
import com.knot.backend.recording.application.dto.result.PresignedAudioUpload;
import com.knot.backend.recording.application.dto.result.RecordingAudioUploadUrlResult;
import com.knot.backend.recording.domain.model.RecordingAudioUpload;
import com.knot.backend.recording.domain.repository.RecordingAudioUploadRepository;
import com.knot.backend.recording.domain.RecordingErrorCode;
import com.knot.backend.recording.domain.RecordingException;
import com.knot.backend.recording.domain.model.RecordingSession;
import com.knot.backend.recording.domain.repository.RecordingSessionRepository;
import com.knot.backend.workspace.domain.model.Workspace;
import com.knot.backend.workspace.domain.repository.WorkspaceMemberRepository;
import com.knot.backend.workspace.domain.repository.WorkspaceRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RecordingAudioUploadServiceTest {
    private static final Instant STARTED_AT = Instant.parse("2026-10-05T00:00:00Z");
    private static final Instant NOW = Instant.parse("2026-10-05T00:30:00Z");
    private static final Instant EXPIRES_AT = NOW.plusSeconds(900);
    private static final long WORKSPACE_ID = 10L;
    private static final long MEMBER_ID = 1L;
    private static final long RECORDING_ID = 7L;
    private static final long UPLOAD_ID = 30L;

    private WorkspaceRepository workspaceRepository;
    private WorkspaceMemberRepository workspaceMemberRepository;
    private RecordingSessionRepository recordingSessionRepository;
    private RecordingAudioUploadRepository recordingAudioUploadRepository;
    private RecordingAudioUploadService service;

    @BeforeEach
    void setUp() {
        workspaceRepository = mock(WorkspaceRepository.class);
        workspaceMemberRepository = mock(WorkspaceMemberRepository.class);
        recordingSessionRepository = mock(RecordingSessionRepository.class);
        recordingAudioUploadRepository = mock(RecordingAudioUploadRepository.class);
        RecordingAudioStorage recordingAudioStorage = mock(RecordingAudioStorage.class);
        when(
                recordingAudioStorage.presignUpload(
                        anyString(),
                        anyString(),
                        anyLong()
                )
        ).thenAnswer(
                invocation -> new PresignedAudioUpload(
                        "https://storage.example/" + invocation.getArgument(0),
                        EXPIRES_AT
                )
        );
        service = new RecordingAudioUploadService(
                new RecordingAccess(
                        workspaceRepository,
                        workspaceMemberRepository,
                        recordingSessionRepository,
                        mock(RecordingControlTokenHasher.class)
                ),
                recordingAudioUploadRepository,
                recordingAudioStorage,
                Clock.fixed(
                        NOW,
                        ZoneOffset.UTC
                ),
                mock(RecordingTranscriptionStateService.class),
                mock(TranscriptionProperties.class)
        );
        when(workspaceRepository.findByIdForUpdate(WORKSPACE_ID)).thenReturn(
                Optional.of(
                        Workspace.create(
                                "Knot 팀",
                                STARTED_AT
                        )
                )
        );
        when(
                workspaceMemberRepository.existsByWorkspaceIdAndMemberId(
                        WORKSPACE_ID,
                        MEMBER_ID
                )
        ).thenReturn(true);
        when(recordingAudioUploadRepository.save(any(RecordingAudioUpload.class))).thenAnswer(invocation -> {
            RecordingAudioUpload saved = spy((RecordingAudioUpload) invocation.getArgument(0));
            doReturn(UPLOAD_ID).when(saved)
                    .getId();
            return saved;
        });
    }

    @Test
    @DisplayName("종료된 본인 녹음은 서버가 정한 key로 업로드를 예약하고 새 URL을 발급한다")
    void issue_success_newReservation() {
        // given
        when(recordingSessionRepository.findByIdForUpdate(RECORDING_ID)).thenReturn(Optional.of(endedSession()));
        when(recordingAudioUploadRepository.findByRecordingId(RECORDING_ID)).thenReturn(Optional.empty());

        // when
        RecordingAudioUploadUrlResult result = service.issue(
                WORKSPACE_ID,
                MEMBER_ID,
                RECORDING_ID,
                command(500L)
        );

        // then
        assertThat(result.created()).isTrue();
        assertThat(result.uploadId()).isEqualTo(UPLOAD_ID);
        assertThat(result.uploadUrl()).startsWith("https://storage.example/recordings/7/");
        assertThat(result.expiresAt()).isEqualTo(EXPIRES_AT);
    }

    @Test
    @DisplayName("완료되지 않은 기존 예약은 같은 uploadId와 key로 URL만 다시 발급한다")
    void issue_success_reissueReservation() {
        // given
        RecordingAudioUpload reserved = spy(
                RecordingAudioUpload.reserve(
                        RECORDING_ID,
                        "recordings/7/existing",
                        "audio/webm",
                        500L,
                        NOW
                )
        );
        doReturn(UPLOAD_ID).when(reserved)
                .getId();
        when(recordingSessionRepository.findByIdForUpdate(RECORDING_ID)).thenReturn(Optional.of(endedSession()));
        when(recordingAudioUploadRepository.findByRecordingId(RECORDING_ID)).thenReturn(Optional.of(reserved));

        // when
        RecordingAudioUploadUrlResult result = service.issue(
                WORKSPACE_ID,
                MEMBER_ID,
                RECORDING_ID,
                command(500L)
        );

        // then
        assertThat(result.created()).isFalse();
        assertThat(result.uploadId()).isEqualTo(UPLOAD_ID);
        assertThat(result.uploadUrl()).isEqualTo("https://storage.example/recordings/7/existing");
        verify(
                recordingAudioUploadRepository,
                never()
        ).save(any(RecordingAudioUpload.class));
    }

    @Test
    @DisplayName("종료 전 녹음은 업로드를 예약하지 않는다")
    void issue_failure_notEnded() {
        // given
        when(recordingSessionRepository.findByIdForUpdate(RECORDING_ID)).thenReturn(Optional.of(session(MEMBER_ID)));

        // when
        Throwable failure = catchThrowable(
                () -> service.issue(
                        WORKSPACE_ID,
                        MEMBER_ID,
                        RECORDING_ID,
                        command(500L)
                )
        );

        // then
        assertThat(failure).isInstanceOf(RecordingException.class)
                .extracting("errorCode")
                .isEqualTo(RecordingErrorCode.RECORDING_NOT_ENDED);
        verify(
                recordingAudioUploadRepository,
                never()
        ).save(any(RecordingAudioUpload.class));
    }

    @Test
    @DisplayName("다른 멤버의 녹음은 업로드를 예약하지 않는다")
    void issue_failure_notStarter() {
        // given
        RecordingSession session = session(MEMBER_ID + 1);
        session.end(STARTED_AT.plusSeconds(60));
        when(recordingSessionRepository.findByIdForUpdate(RECORDING_ID)).thenReturn(Optional.of(session));

        // when
        Throwable failure = catchThrowable(
                () -> service.issue(
                        WORKSPACE_ID,
                        MEMBER_ID,
                        RECORDING_ID,
                        command(500L)
                )
        );

        // then
        assertThat(failure).isInstanceOf(RecordingException.class)
                .extracting("errorCode")
                .isEqualTo(RecordingErrorCode.RECORDING_CONTROL_DENIED);
    }

    @Test
    @DisplayName("최대 크기를 넘는 파일은 예약을 저장하지 않고 거절한다")
    void issue_failure_tooLarge() {
        // given
        when(recordingSessionRepository.findByIdForUpdate(RECORDING_ID)).thenReturn(Optional.of(endedSession()));
        when(recordingAudioUploadRepository.findByRecordingId(RECORDING_ID)).thenReturn(Optional.empty());

        // when
        Throwable failure = catchThrowable(
                () -> service.issue(
                        WORKSPACE_ID,
                        MEMBER_ID,
                        RECORDING_ID,
                        command(500L * 1024 * 1024 + 1)
                )
        );

        // then
        assertThat(failure).isInstanceOf(RecordingException.class)
                .extracting("errorCode")
                .isEqualTo(RecordingErrorCode.INVALID_AUDIO_UPLOAD);
        verify(
                recordingAudioUploadRepository,
                never()
        ).save(any(RecordingAudioUpload.class));
    }

    private RecordingAudioUploadUrlCommand command(long contentLength) {
        return new RecordingAudioUploadUrlCommand(
                "audio/webm",
                contentLength
        );
    }

    private RecordingSession endedSession() {
        RecordingSession session = session(MEMBER_ID);
        session.end(STARTED_AT.plusSeconds(60));
        return session;
    }

    private RecordingSession session(long memberId) {
        return RecordingSession.start(
                WORKSPACE_ID,
                memberId,
                UUID.randomUUID(),
                UUID.randomUUID(),
                "a".repeat(64),
                STARTED_AT
        );
    }
}
