package com.knot.backend.recording.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.knot.backend.recording.domain.RecordingErrorCode;
import com.knot.backend.recording.domain.RecordingException;
import java.util.Base64;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class AudioChunkDescriptorTest {
    private static final String HASH = Base64.getEncoder()
            .encodeToString(new byte[32]);

    @ParameterizedTest
    @CsvSource({"1,0,1,2", "1440,115120000,80000,160000"})
    @DisplayName("한 프레임과 정확히 두 시간 끝 경계를 유효한 조각으로 허용한다")
    void create_success_boundary(
            int sequence,
            long offset,
            int frames,
            long bytes
    ) {
        // given

        // when
        AudioChunkDescriptor descriptor = new AudioChunkDescriptor(
                sequence,
                offset,
                frames,
                bytes,
                HASH
        );

        // then
        assertThat(descriptor.frameCount()).isEqualTo(frames);
    }

    @ParameterizedTest
    @CsvSource({"0,0,1,2", "1,-1,1,2", "1,0,0,0", "1,0,80001,160002", "1,0,1,1", "1,115200000,1,2",
            "1,9223372036854775807,1,2"})
    @DisplayName("번호와 프레임과 바이트 수와 총 길이의 잘못된 경계를 거절한다")
    void create_failure_invalidRange(
            int sequence,
            long offset,
            int frames,
            long bytes
    ) {
        // given

        // when
        Throwable thrown = catchThrowable(
                () -> new AudioChunkDescriptor(
                        sequence,
                        offset,
                        frames,
                        bytes,
                        HASH
                )
        );

        // then
        assertThat(thrown).isInstanceOf(RecordingException.class)
                .extracting("errorCode")
                .isEqualTo(RecordingErrorCode.INVALID_AUDIO_CHUNK);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"AAAA", "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
            "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAB="})
    @DisplayName("정규 Base64 SHA256이 아닌 해시를 거절한다")
    void create_failure_invalidChecksum(String checksum) {
        // given

        // when
        Throwable thrown = catchThrowable(
                () -> new AudioChunkDescriptor(
                        1,
                        0,
                        1,
                        2,
                        checksum
                )
        );

        // then
        assertThat(thrown).isInstanceOf(RecordingException.class)
                .extracting("errorCode")
                .isEqualTo(RecordingErrorCode.INVALID_AUDIO_CHUNK);
    }
}
