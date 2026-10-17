package com.knot.backend.recording.infrastructure.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import com.knot.backend.recording.application.RecordingAudioStorage;
import com.knot.backend.recording.domain.RecordingErrorCode;
import com.knot.backend.recording.domain.RecordingException;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class RecordingAudioStorageConfigTest {

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = " ")
    @DisplayName("버킷이 없으면 서버는 뜨고 업로드 요청만 저장소 사용 불가로 거절한다")
    void recordingAudioStorage_success_missingBucketRejectsUpload(String bucket) {
        // given
        RecordingAudioStorage recordingAudioStorage = new RecordingAudioStorageConfig().recordingAudioStorage(
                properties(
                        "ap-northeast-2",
                        bucket
                )
        );

        // when
        Throwable failure = catchThrowable(
                () -> recordingAudioStorage.presignUpload(
                        "recordings/1/key",
                        "audio/webm",
                        10L
                )
        );

        // then
        assertThat(recordingAudioStorage).isInstanceOf(UnconfiguredRecordingAudioStorage.class);
        assertThat(failure).isInstanceOf(RecordingException.class)
                .extracting("errorCode")
                .isEqualTo(RecordingErrorCode.AUDIO_STORAGE_UNAVAILABLE);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = " ")
    @DisplayName("리전이 없으면 저장소 미설정으로 처리한다")
    void recordingAudioStorage_success_missingRegion(String region) {
        // given
        RecordingAudioStorageProperties properties = properties(
                region,
                "knot-audio"
        );

        // when
        RecordingAudioStorage recordingAudioStorage = new RecordingAudioStorageConfig()
                .recordingAudioStorage(properties);

        // then
        assertThat(recordingAudioStorage).isInstanceOf(UnconfiguredRecordingAudioStorage.class);
    }

    @Test
    @DisplayName("리전과 버킷만 있으면 자격 증명 조회 없이 AWS S3 저장소를 구성한다")
    void recordingAudioStorage_success_defaultCredentials() {
        // given
        RecordingAudioStorageProperties properties = properties(
                "ap-northeast-2",
                "knot-audio"
        );

        // when
        RecordingAudioStorage recordingAudioStorage = new RecordingAudioStorageConfig()
                .recordingAudioStorage(properties);

        // then
        assertThat(recordingAudioStorage).isInstanceOf(S3RecordingAudioStorage.class);
    }

    private RecordingAudioStorageProperties properties(
            String region,
            String bucket
    ) {
        return new RecordingAudioStorageProperties(
                region,
                bucket,
                "",
                Duration.ofMinutes(15)
        );
    }
}
