package com.knot.backend.recording.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.knot.backend.recording.domain.RecordingErrorCode;
import com.knot.backend.recording.domain.RecordingException;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RecordingAudioModeTest {
    private static final String HASH = "0".repeat(64);
    private static final UUID TAB = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-10-17T00:00:00Z");

    @Test
    @DisplayName("모드를 생략한 녹음은 기존 최종 WebM 모드로 시작한다")
    void start_success_legacyDefault() {
        // given

        // when
        RecordingSession session = RecordingSession.start(
                1L,
                2L,
                UUID.randomUUID(),
                TAB,
                HASH,
                NOW
        );

        // then
        assertThat(session.getAudioMode()).isEqualTo(RecordingAudioMode.FINAL_WEBM_V1);
    }

    @Test
    @DisplayName("PCM 모드의 시작 재요청은 같은 모드를 유지한다")
    void validateStartRetry_success_sameMode() {
        // given
        RecordingSession session = pcmSession();

        // when
        session.validateStartRetry(
                1,
                TAB,
                HASH,
                RecordingAudioMode.PCM16_CHUNKS_V1
        );

        // then
        assertThat(session.getAudioMode()).isEqualTo(RecordingAudioMode.PCM16_CHUNKS_V1);
    }

    @Test
    @DisplayName("같은 시작 요청에서 오디오 모드를 변경하면 충돌한다")
    void validateStartRetry_failure_modeConflict() {
        // given
        RecordingSession session = pcmSession();

        // when
        Throwable thrown = catchThrowable(
                () -> session.validateStartRetry(
                        1,
                        TAB,
                        HASH
                )
        );

        // then
        assertThat(thrown).isInstanceOf(RecordingException.class)
                .extracting("errorCode")
                .isEqualTo(RecordingErrorCode.AUDIO_MODE_CONFLICT);
    }

    @Test
    @DisplayName("PCM 녹음에서 기존 최종 파일 업로드를 거절한다")
    void validateLegacyAudioMode_failure_pcmMode() {
        // given
        RecordingSession session = pcmSession();

        // when
        Throwable thrown = catchThrowable(session::validateLegacyAudioMode);

        // then
        assertThat(thrown).isInstanceOf(RecordingException.class)
                .extracting("errorCode")
                .isEqualTo(RecordingErrorCode.AUDIO_MODE_CONFLICT);
    }

    private RecordingSession pcmSession() {
        return RecordingSession.start(
                1L,
                2L,
                UUID.randomUUID(),
                TAB,
                HASH,
                NOW,
                RecordingAudioMode.PCM16_CHUNKS_V1
        );
    }
}
