package com.knot.backend.recording.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.knot.backend.global.config.TranscriptionProperties;
import com.knot.backend.recording.application.dto.command.RecordingAudioUploadUrlCommand;
import com.knot.backend.recording.domain.RecordingErrorCode;
import com.knot.backend.recording.domain.RecordingException;
import com.knot.backend.recording.domain.model.RecordingAudioMode;
import com.knot.backend.recording.domain.model.RecordingSession;
import com.knot.backend.recording.domain.model.StoredAudioObject;
import com.knot.backend.recording.domain.repository.RecordingAudioUploadRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class RecordingLegacyAudioModeTest {
    private final RecordingAccess access = mock(RecordingAccess.class);
    private final RecordingAudioUploadRepository uploads = mock(RecordingAudioUploadRepository.class);
    private final RecordingAudioStorage storage = mock(RecordingAudioStorage.class);
    private final RecordingTranscriptionStateService transcriptions = mock(RecordingTranscriptionStateService.class);

    @Test
    @DisplayName("PCM 세션은 종료됐어도 기존 WebM 업로드 URL을 예약하거나 서명하지 못한다")
    void issue_failure_pcmMode() {
        // given
        when(
                access.loadOwnedForUpdate(
                        10L,
                        1L,
                        7L
                )
        ).thenReturn(pcmSession());
        RecordingAudioUploadUrlService service = new RecordingAudioUploadUrlService(
                access,
                uploads,
                storage,
                Clock.systemUTC()
        );

        // when
        Throwable failure = catchThrowable(
                () -> service.issue(
                        10L,
                        1L,
                        7L,
                        new RecordingAudioUploadUrlCommand(
                                "audio/webm",
                                1024
                        )
                )
        );

        // then
        assertThat(failure).isInstanceOf(RecordingException.class)
                .extracting("errorCode")
                .isEqualTo(RecordingErrorCode.AUDIO_MODE_CONFLICT);
        verifyNoInteractions(
                uploads,
                storage
        );
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @DisplayName("PCM 세션은 기존 WebM 완료 확인과 완료 저장 모두 예약 조회 전에 거절한다")
    void complete_failure_pcmMode(boolean committing) {
        // given
        when(
                access.loadOwnedForUpdate(
                        10L,
                        1L,
                        7L
                )
        ).thenReturn(pcmSession());
        RecordingAudioUploadCompletionTransaction transaction = new RecordingAudioUploadCompletionTransaction(
                access,
                uploads,
                Clock.systemUTC(),
                transcriptions,
                mock(TranscriptionProperties.class)
        );

        // when
        Throwable failure = catchThrowable(() -> {
            if (committing) {
                transaction.complete(
                        10L,
                        1L,
                        7L,
                        30L,
                        StoredAudioObject.missing()
                );
            } else {
                transaction.findStorageKeyToConfirm(
                        10L,
                        1L,
                        7L,
                        30L
                );
            }
        });

        // then
        assertThat(failure).isInstanceOf(RecordingException.class)
                .extracting("errorCode")
                .isEqualTo(RecordingErrorCode.AUDIO_MODE_CONFLICT);
        verifyNoInteractions(
                uploads,
                storage,
                transcriptions
        );
    }

    private RecordingSession pcmSession() {
        Instant startedAt = Instant.parse("2026-10-17T10:00:00Z");
        RecordingSession session = RecordingSession.start(
                10L,
                1L,
                UUID.randomUUID(),
                UUID.randomUUID(),
                "a".repeat(64),
                startedAt,
                RecordingAudioMode.PCM16_CHUNKS_V1
        );
        session.end(startedAt.plusSeconds(10));
        return session;
    }
}
