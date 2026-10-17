package com.knot.backend.recording.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.knot.backend.recording.domain.RecordingErrorCode;
import com.knot.backend.recording.domain.RecordingException;
import java.time.Instant;
import java.util.Base64;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class RecordingAudioChunkTest {
    private static final String HASH = Base64.getEncoder()
            .encodeToString(new byte[32]);
    private static final Instant NOW = Instant.parse("2026-10-17T00:00:00Z");

    @Test
    @DisplayName("저장 객체의 크기와 해시와 버전이 일치하면 조각을 검증 완료한다")
    void verify_success() {
        // given
        RecordingAudioChunk chunk = chunk();

        // when
        chunk.verify(
                stored("version-1"),
                NOW
        );

        // then
        assertThat(chunk.getStatus()).isEqualTo(RecordingAudioChunkStatus.VERIFIED);
        assertThat(chunk.getStorageVersionId()).isEqualTo("version-1");
        assertThat(chunk.getVerifiedAt()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("같은 버전의 완료 재요청은 처음 확정한 시각을 유지한다")
    void verify_success_sameVersionRetry() {
        // given
        RecordingAudioChunk chunk = chunk();
        chunk.verify(
                stored("version-1"),
                NOW
        );

        // when
        chunk.verify(
                stored("version-1"),
                NOW.plusSeconds(5)
        );

        // then
        assertThat(chunk.getVerifiedAt()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("검증 완료한 조각은 다른 객체 버전으로 변경할 수 없다")
    void verify_failure_differentVersion() {
        // given
        RecordingAudioChunk chunk = chunk();
        chunk.verify(
                stored("version-1"),
                NOW
        );

        // when
        Throwable thrown = catchThrowable(
                () -> chunk.verify(
                        stored("version-2"),
                        NOW
                )
        );

        // then
        assertError(
                thrown,
                RecordingErrorCode.AUDIO_CHUNK_MISMATCH
        );
        assertThat(chunk.getStorageVersionId()).isEqualTo("version-1");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "null"})
    @DisplayName("유효한 버전이 없는 객체는 검증 완료할 수 없다")
    void verify_failure_invalidVersion(String versionId) {
        // given
        RecordingAudioChunk chunk = chunk();

        // when
        Throwable thrown = catchThrowable(
                () -> chunk.verify(
                        stored(versionId),
                        NOW
                )
        );

        // then
        assertError(
                thrown,
                RecordingErrorCode.AUDIO_CHUNK_MISMATCH
        );
        assertThat(chunk.getStatus()).isEqualTo(RecordingAudioChunkStatus.RESERVED);
    }

    @ParameterizedTest
    @CsvSource({"159998,application/octet-stream", "160000,audio/webm"})
    @DisplayName("예약과 크기 또는 타입이 다른 객체는 거절한다")
    void verify_failure_mismatchedObject(
            long length,
            String type
    ) {
        // given
        RecordingAudioChunk chunk = chunk();
        StoredAudioChunk stored = new StoredAudioChunk(
                true,
                length,
                type,
                HASH,
                "version-1"
        );

        // when
        Throwable thrown = catchThrowable(
                () -> chunk.verify(
                        stored,
                        NOW
                )
        );

        // then
        assertError(
                thrown,
                RecordingErrorCode.AUDIO_CHUNK_MISMATCH
        );
    }

    @Test
    @DisplayName("해시가 다른 객체는 검증 완료하지 않는다")
    void verify_failure_checksumMismatch() {
        // given
        RecordingAudioChunk chunk = chunk();
        StoredAudioChunk stored = new StoredAudioChunk(
                true,
                160000,
                "application/octet-stream",
                "wrong",
                "v1"
        );

        // when
        Throwable thrown = catchThrowable(
                () -> chunk.verify(
                        stored,
                        NOW
                )
        );

        // then
        assertError(
                thrown,
                RecordingErrorCode.AUDIO_CHUNK_MISMATCH
        );
    }

    @Test
    @DisplayName("저장되지 않은 객체는 미저장 오류를 반환한다")
    void verify_failure_missingObject() {
        // given
        RecordingAudioChunk chunk = chunk();

        // when
        Throwable thrown = catchThrowable(
                () -> chunk.verify(
                        StoredAudioChunk.missing(),
                        NOW
                )
        );

        // then
        assertError(
                thrown,
                RecordingErrorCode.AUDIO_CHUNK_NOT_STORED
        );
    }

    @Test
    @DisplayName("같은 번호의 다른 해시를 예약할 수 없다")
    void validateDescriptor_failure_checksumConflict() {
        // given
        RecordingAudioChunk chunk = chunk();
        byte[] hash = new byte[32];
        hash[0] = 1;
        AudioChunkDescriptor different = new AudioChunkDescriptor(
                1,
                0,
                80000,
                160000,
                Base64.getEncoder()
                        .encodeToString(hash)
        );

        // when
        Throwable thrown = catchThrowable(() -> chunk.validateDescriptor(different));

        // then
        assertError(
                thrown,
                RecordingErrorCode.AUDIO_CHUNK_CONFLICT
        );
    }

    @Test
    @DisplayName("URL 갱신은 정리 추적용 최대 만료 시각을 줄이지 않는다")
    void extendUploadUrlExpiry_success_preserveMaximum() {
        // given
        RecordingAudioChunk chunk = chunk();

        // when
        chunk.extendUploadUrlExpiry(NOW.plusSeconds(1));

        // then
        assertThat(chunk.getUploadUrlExpiresAt()).isEqualTo(NOW.plusSeconds(60));
    }

    private RecordingAudioChunk chunk() {
        return RecordingAudioChunk.reserve(
                1,
                new AudioChunkDescriptor(
                        1,
                        0,
                        80000,
                        160000,
                        HASH
                ),
                "recordings/1/chunks/1",
                NOW.plusSeconds(60)
        );
    }

    private StoredAudioChunk stored(String version) {
        return new StoredAudioChunk(
                true,
                160000,
                "application/octet-stream",
                HASH,
                version
        );
    }

    private void assertError(
            Throwable thrown,
            RecordingErrorCode code
    ) {
        assertThat(thrown).isInstanceOf(RecordingException.class)
                .extracting("errorCode")
                .isEqualTo(code);
    }
}
