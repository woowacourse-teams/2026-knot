package com.knot.backend.recording.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.knot.backend.recording.domain.RecordingErrorCode;
import com.knot.backend.recording.domain.RecordingException;
import com.knot.backend.recording.domain.AudioSequenceGapException;
import java.util.Base64;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RecordingAudioStreamTest {
    private static final String HASH = Base64.getEncoder()
            .encodeToString(new byte[32]);

    @Test
    @DisplayName("연속 번호와 offset의 조각을 등록하면 다음 위치를 전진한다")
    void reserve_success() {
        // given
        RecordingAudioStream stream = RecordingAudioStream.create(1);
        stream.reserve(
                new AudioChunkDescriptor(
                        1,
                        0,
                        80000,
                        160000,
                        HASH
                )
        );

        // when
        stream.reserve(
                new AudioChunkDescriptor(
                        2,
                        80000,
                        1,
                        2,
                        HASH
                )
        );

        // then
        assertThat(stream.getNextSequence()).isEqualTo(3);
        assertThat(stream.getTotalFrameCount()).isEqualTo(80001);
    }

    @Test
    @DisplayName("번호가 앞서면 다음 기대 번호를 포함한 오류를 반환한다")
    void reserve_failure_sequenceGap() {
        // given
        RecordingAudioStream stream = RecordingAudioStream.create(1);

        // when
        Throwable thrown = catchThrowable(
                () -> stream.reserve(
                        new AudioChunkDescriptor(
                                2,
                                0,
                                1,
                                2,
                                HASH
                        )
                )
        );

        // then
        assertThat(thrown).isInstanceOf(AudioSequenceGapException.class)
                .extracting("expectedNextSequence")
                .isEqualTo(1);
        assertThat(stream.getNextSequence()).isEqualTo(1);
    }

    @Test
    @DisplayName("연속 번호여도 offset이 다르면 등록하지 않는다")
    void reserve_failure_offsetConflict() {
        // given
        RecordingAudioStream stream = RecordingAudioStream.create(1);

        // when
        Throwable thrown = catchThrowable(
                () -> stream.reserve(
                        new AudioChunkDescriptor(
                                1,
                                1,
                                1,
                                2,
                                HASH
                        )
                )
        );

        // then
        assertThat(thrown).isInstanceOf(RecordingException.class)
                .extracting("errorCode")
                .isEqualTo(RecordingErrorCode.AUDIO_CHUNK_CONFLICT);
        assertThat(stream.getTotalFrameCount()).isZero();
    }

    @Test
    @DisplayName("범위를 고정한 stream에는 새 조각을 등록할 수 없다")
    void reserve_failure_frozenStream() {
        // given
        RecordingAudioStream stream = RecordingAudioStream.create(1);
        stream.freeze();

        // when
        Throwable thrown = catchThrowable(
                () -> stream.reserve(
                        new AudioChunkDescriptor(
                                1,
                                0,
                                1,
                                2,
                                HASH
                        )
                )
        );

        // then
        assertThat(thrown).isInstanceOf(RecordingException.class)
                .extracting("errorCode")
                .isEqualTo(RecordingErrorCode.AUDIO_SAVE_FROZEN);
    }

    @Test
    @DisplayName("확인된 조각 바로 뒤로 되돌리면 다음 예약 위치가 그 뒤 순번과 누적 프레임이 된다")
    void rewindTo_success() {
        // given
        RecordingAudioStream stream = RecordingAudioStream.create(1);
        stream.reserve(
                descriptor(
                        1,
                        0
                )
        );
        stream.reserve(
                descriptor(
                        2,
                        10
                )
        );

        // when
        stream.rewindTo(
                1,
                10
        );

        // then
        assertThat(stream.getNextSequence()).isEqualTo(2);
        assertThat(stream.getTotalFrameCount()).isEqualTo(10);
        assertThat(
                catchThrowable(
                        () -> stream.reserve(
                                descriptor(
                                        2,
                                        10
                                )
                        )
                )
        ).isNull();
    }

    @Test
    @DisplayName("예약한 위치보다 앞으로는 되돌릴 수 있지만 뒤로 건너뛸 수는 없다")
    void rewindTo_failure_beyondReserved() {
        // given
        RecordingAudioStream stream = RecordingAudioStream.create(1);
        stream.reserve(
                descriptor(
                        1,
                        0
                )
        );

        // when
        Throwable thrown = catchThrowable(
                () -> stream.rewindTo(
                        2,
                        20
                )
        );

        // then
        assertThat(thrown).isInstanceOf(RecordingException.class)
                .extracting("errorCode")
                .isEqualTo(RecordingErrorCode.INVALID_AUDIO_CHUNK);
        assertThat(stream.getNextSequence()).isEqualTo(2);
    }

    @Test
    @DisplayName("봉인한 stream은 되돌리지 않는다")
    void rewindTo_failure_sealed() {
        // given
        RecordingAudioStream stream = RecordingAudioStream.create(1);
        stream.seal(
                RecordingAudioSealKind.REMOTE,
                java.time.Instant.parse("2026-10-28T00:00:00Z")
        );

        // when
        Throwable thrown = catchThrowable(
                () -> stream.rewindTo(
                        0,
                        0
                )
        );

        // then
        assertThat(thrown).isInstanceOf(RecordingException.class)
                .extracting("errorCode")
                .isEqualTo(RecordingErrorCode.AUDIO_SEAL_CONFLICT);
    }

    private AudioChunkDescriptor descriptor(
            int sequence,
            long sampleOffset
    ) {
        return new AudioChunkDescriptor(
                sequence,
                sampleOffset,
                10,
                20,
                HASH
        );
    }
}
