package com.knot.backend.recording.domain.model;

import com.knot.backend.recording.domain.RecordingErrorCode;
import com.knot.backend.recording.domain.RecordingException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class RecordingAudioUploadTest {
    private static final Instant RESERVED_AT = Instant.parse("2026-10-05T00:00:00Z");

    @Test
    @DisplayName("업로드를 예약하면 RESERVED 상태로 파일 정보와 서버가 정한 저장소 key를 저장한다")
    void reserve_success() {
        // given
        String storageKey = "recordings/1/key";

        // when
        RecordingAudioUpload upload = RecordingAudioUpload.reserve(
                1L,
                storageKey,
                "audio/webm",
                1024L,
                RESERVED_AT
        );

        // then
        assertThat(upload.getStatus()).isEqualTo(RecordingAudioUploadStatus.RESERVED);
        assertThat(upload.getStorageKey()).isEqualTo(storageKey);
        assertThat(upload.getContentType()).isEqualTo("audio/webm");
        assertThat(upload.getContentLength()).isEqualTo(1024L);
        assertThat(upload.getCompletedAt()).isNull();
    }

    @ParameterizedTest
    @CsvSource({"audio/mpeg,1024", "audio/webm,0", "audio/webm,524288001"})
    @DisplayName("허용되지 않은 형식, 0 이하 크기, 500MB 초과 파일은 예약하지 않는다")
    void reserve_failure_disallowedFile(
            String contentType,
            long contentLength
    ) {
        // given
        String storageKey = "recordings/1/key";

        // when
        Throwable failure = catchThrowable(
                () -> RecordingAudioUpload.reserve(
                        1L,
                        storageKey,
                        contentType,
                        contentLength,
                        RESERVED_AT
                )
        );

        // then
        assertThat(failure).isInstanceOf(RecordingException.class)
                .extracting("errorCode")
                .isEqualTo(RecordingErrorCode.INVALID_AUDIO_UPLOAD);
    }

    @Test
    @DisplayName("저장소 key가 없으면 예약하지 않는다")
    void reserve_failure_blankStorageKey() {
        // given
        String storageKey = " ";

        // when
        Throwable failure = catchThrowable(
                () -> RecordingAudioUpload.reserve(
                        1L,
                        storageKey,
                        "audio/webm",
                        1024L,
                        RESERVED_AT
                )
        );

        // then
        assertThat(failure).isInstanceOf(RecordingException.class)
                .extracting("errorCode")
                .isEqualTo(RecordingErrorCode.INVALID_RECORDING_DATA);
    }

    @Test
    @DisplayName("완료되지 않은 예약은 같은 형식·크기로 재발급할 수 있다")
    void validateReissuable_success_sameFile() {
        // given
        RecordingAudioUpload upload = reservedUpload();

        // when
        Throwable failure = catchThrowable(
                () -> upload.validateReissuable(
                        "audio/webm",
                        1024L
                )
        );

        // then
        assertThat(failure).isNull();
    }

    @Test
    @DisplayName("재발급 때 파일 크기가 다르면 이전 URL과 다른 파일이 같은 key에 올라갈 수 있어 거절한다")
    void validateReissuable_failure_differentFile() {
        // given
        RecordingAudioUpload upload = reservedUpload();

        // when
        Throwable failure = catchThrowable(
                () -> upload.validateReissuable(
                        "audio/webm",
                        2048L
                )
        );

        // then
        assertThat(failure).isInstanceOf(RecordingException.class)
                .extracting("errorCode")
                .isEqualTo(RecordingErrorCode.INVALID_AUDIO_UPLOAD);
    }

    private RecordingAudioUpload reservedUpload() {
        return RecordingAudioUpload.reserve(
                1L,
                "recordings/1/key",
                "audio/webm",
                1024L,
                RESERVED_AT
        );
    }

    @Test
    @DisplayName("저장된 파일의 크기와 형식이 예약과 같으면 확인한 저장소 버전과 함께 업로드를 완료한다")
    void complete_success_matchingFile() {
        // given
        RecordingAudioUpload upload = reserved();

        // when
        upload.complete(
                StoredAudioObject.of(
                        1024L,
                        "audio/webm",
                        "version-1"
                ),
                RESERVED_AT.plusSeconds(60)
        );

        // then
        assertThat(upload.getStatus()).isEqualTo(RecordingAudioUploadStatus.COMPLETED);
        assertThat(upload.getCompletedAt()).isEqualTo(RESERVED_AT.plusSeconds(60));
        assertThat(upload.getStorageVersionId()).isEqualTo("version-1");
    }

    @Test
    @DisplayName("저장된 파일의 크기가 예약과 다르면 업로드를 완료하지 않는다")
    void complete_failure_lengthMismatch() {
        // given
        RecordingAudioUpload upload = reserved();

        // when
        Throwable failure = catchThrowable(
                () -> upload.complete(
                        StoredAudioObject.of(
                                1000L,
                                "audio/webm",
                                "version-1"
                        ),
                        RESERVED_AT.plusSeconds(60)
                )
        );

        // then
        assertThat(failure).isInstanceOf(RecordingException.class)
                .extracting("errorCode")
                .isEqualTo(RecordingErrorCode.AUDIO_UPLOAD_NOT_COMPLETED);
        assertThat(upload.getStatus()).isEqualTo(RecordingAudioUploadStatus.RESERVED);
    }

    @Test
    @DisplayName("저장된 파일의 형식이 예약과 다르면 업로드를 완료하지 않는다")
    void complete_failure_typeMismatch() {
        // given
        RecordingAudioUpload upload = reserved();

        // when
        Throwable failure = catchThrowable(
                () -> upload.complete(
                        StoredAudioObject.of(
                                1024L,
                                "audio/mpeg",
                                "version-1"
                        ),
                        RESERVED_AT.plusSeconds(60)
                )
        );

        // then
        assertThat(failure).isInstanceOf(RecordingException.class)
                .extracting("errorCode")
                .isEqualTo(RecordingErrorCode.AUDIO_UPLOAD_NOT_COMPLETED);
    }

    @Test
    @DisplayName("이미 완료된 업로드는 다시 완료해도 처음 완료 시각과 저장소 버전을 유지한다")
    void complete_success_repeatedKeepsCompletedAt() {
        // given
        RecordingAudioUpload upload = reserved();
        upload.complete(
                StoredAudioObject.of(
                        1024L,
                        "audio/webm",
                        "version-1"
                ),
                RESERVED_AT.plusSeconds(60)
        );

        // when
        upload.complete(
                StoredAudioObject.of(
                        1024L,
                        "audio/webm",
                        "version-2"
                ),
                RESERVED_AT.plusSeconds(120)
        );

        // then
        assertThat(upload.getCompletedAt()).isEqualTo(RESERVED_AT.plusSeconds(60));
        assertThat(upload.getStorageVersionId()).isEqualTo("version-1");
    }

    @Test
    @DisplayName("다른 녹음의 업로드 예약은 찾을 수 없는 것으로 거절한다")
    void validateBelongsTo_failure_otherRecording() {
        // given
        RecordingAudioUpload upload = reserved();

        // when
        Throwable failure = catchThrowable(() -> upload.validateBelongsTo(2L));

        // then
        assertThat(failure).isInstanceOf(RecordingException.class)
                .extracting("errorCode")
                .isEqualTo(RecordingErrorCode.AUDIO_UPLOAD_NOT_FOUND);
    }

    @Test
    @DisplayName("완료되고 보존 삭제되지 않은 업로드는 사용할 수 있는 오디오다")
    void isAudioAvailable_success_completedUpload() {
        // given
        RecordingAudioUpload upload = completed();

        // when
        boolean available = upload.isAudioAvailable();

        // then
        assertThat(available).isTrue();
    }

    @Test
    @DisplayName("예약만 된 업로드는 사용할 수 있는 오디오가 아니다")
    void isAudioAvailable_success_falseWhenReserved() {
        // given
        RecordingAudioUpload upload = reserved();

        // when
        boolean available = upload.isAudioAvailable();

        // then
        assertThat(available).isFalse();
    }

    @Test
    @DisplayName("보존 삭제된 업로드는 사용할 수 있는 오디오가 아니다")
    void isAudioAvailable_success_falseWhenDeleted() {
        // given
        RecordingAudioUpload upload = completed();
        upload.recordDeleted(RESERVED_AT.plusSeconds(120));

        // when
        boolean available = upload.isAudioAvailable();

        // then
        assertThat(available).isFalse();
    }

    @Test
    @DisplayName("사용할 수 있는 오디오는 검증을 통과한다")
    void validateAudioAvailable_success_completedUpload() {
        // given
        RecordingAudioUpload upload = completed();

        // when
        Throwable failure = catchThrowable(upload::validateAudioAvailable);

        // then
        assertThat(failure).isNull();
    }

    @Test
    @DisplayName("예약만 된 업로드는 업로드 미완료 오류로 거절한다")
    void validateAudioAvailable_failure_reservedUpload() {
        // given
        RecordingAudioUpload upload = reserved();

        // when
        Throwable failure = catchThrowable(upload::validateAudioAvailable);

        // then
        assertThat(failure).isInstanceOf(RecordingException.class)
                .extracting("errorCode")
                .isEqualTo(RecordingErrorCode.AUDIO_UPLOAD_NOT_COMPLETED);
    }

    @Test
    @DisplayName("보존 삭제된 업로드는 업로드 미완료 오류로 거절한다")
    void validateAudioAvailable_failure_deletedUpload() {
        // given
        RecordingAudioUpload upload = completed();
        upload.recordDeleted(RESERVED_AT.plusSeconds(120));

        // when
        Throwable failure = catchThrowable(upload::validateAudioAvailable);

        // then
        assertThat(failure).isInstanceOf(RecordingException.class)
                .extracting("errorCode")
                .isEqualTo(RecordingErrorCode.AUDIO_UPLOAD_NOT_COMPLETED);
    }

    @Test
    @DisplayName("저장소에 파일이 없으면 업로드 미완료 오류로 거절한다")
    void complete_failure_missingObject() {
        // given
        RecordingAudioUpload upload = reserved();

        // when
        Throwable failure = catchThrowable(
                () -> upload.complete(
                        StoredAudioObject.missing(),
                        RESERVED_AT.plusSeconds(60)
                )
        );

        // then
        assertThat(failure).isInstanceOf(RecordingException.class)
                .extracting("errorCode")
                .isEqualTo(RecordingErrorCode.AUDIO_UPLOAD_NOT_COMPLETED);
        assertThat(upload.isCompleted()).isFalse();
    }

    @Test
    @DisplayName("이미 완료된 업로드는 저장소 파일이 없어도 완료 상태를 유지한다")
    void complete_success_completedIgnoresMissingObject() {
        // given
        RecordingAudioUpload upload = completed();

        // when
        upload.complete(
                StoredAudioObject.missing(),
                RESERVED_AT.plusSeconds(120)
        );

        // then
        assertThat(upload.getCompletedAt()).isEqualTo(RESERVED_AT.plusSeconds(60));
        assertThat(upload.getStorageVersionId()).isEqualTo("version-1");
    }

    @Test
    @DisplayName("완료 전 업로드는 저장소에서 확인할 key를 돌려준다")
    void findStorageKeyToConfirm_success_reserved() {
        // given
        RecordingAudioUpload upload = reserved();

        // when
        Optional<String> storageKey = upload.findStorageKeyToConfirm();

        // then
        assertThat(storageKey).contains("recordings/1/key");
    }

    @Test
    @DisplayName("이미 완료된 업로드는 저장소를 다시 확인하지 않는다")
    void findStorageKeyToConfirm_success_emptyWhenCompleted() {
        // given
        RecordingAudioUpload upload = completed();

        // when
        Optional<String> storageKey = upload.findStorageKeyToConfirm();

        // then
        assertThat(storageKey).isEmpty();
    }

    @Test
    @DisplayName("접근 철회는 물리 삭제 이력과 구분하고 완료된 음원을 영구 비활성화한다")
    void revokeDisablesAvailableAudioWithoutClaimingPhysicalDeletion() {
        // given
        RecordingAudioUpload upload = completed();
        // when
        upload.revoke(RESERVED_AT.plusSeconds(61));
        // then
        assertThat(upload.isAudioAvailable()).isFalse();
        assertThat(upload.getDeletedAt()).isNull();
        assertThat(upload.getRevokedAt()).isEqualTo(RESERVED_AT.plusSeconds(61));
    }

    @Test
    @DisplayName("철회 뒤 완료를 반복해도 음원이 다시 활성화되지 않는다")
    void completedRetryCannotReactivateRevokedUpload() {
        // given
        RecordingAudioUpload upload = completed();
        upload.revoke(RESERVED_AT.plusSeconds(61));
        // when
        upload.complete(
                StoredAudioObject.of(
                        1024L,
                        "audio/webm",
                        "version-1"
                ),
                RESERVED_AT.plusSeconds(62)
        );
        // then
        assertThat(upload.isAudioAvailable()).isFalse();
        assertThat(upload.getRevokedAt()).isEqualTo(RESERVED_AT.plusSeconds(61));
    }

    private RecordingAudioUpload completed() {
        RecordingAudioUpload upload = reserved();
        upload.complete(
                StoredAudioObject.of(
                        1024L,
                        "audio/webm",
                        "version-1"
                ),
                RESERVED_AT.plusSeconds(60)
        );
        return upload;
    }

    private RecordingAudioUpload reserved() {
        return RecordingAudioUpload.reserve(
                1L,
                "recordings/1/key",
                "audio/webm",
                1024L,
                RESERVED_AT
        );
    }
}
