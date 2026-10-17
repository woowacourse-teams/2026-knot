package com.knot.backend.recording.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import com.knot.backend.recording.application.dto.result.RecordingAudioUploadCompletionResult;
import com.knot.backend.recording.domain.RecordingAudioUploadStatus;
import com.knot.backend.recording.domain.RecordingErrorCode;
import com.knot.backend.recording.domain.RecordingException;
import com.knot.backend.recording.domain.StoredAudioObject;
import com.knot.backend.testsupport.LogCapture;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RecordingUploadLoggingTest {
    private static final long WORKSPACE_ID = 10L;
    private static final long MEMBER_ID = 1L;
    private static final long RECORDING_ID = 7L;
    private static final long UPLOAD_ID = 30L;
    private static final String STORAGE_KEY = "private-object-key";

    private RecordingAudioUploadCompletionTransaction completionTransaction;
    private RecordingAudioStorage storage;
    private RecordingTranscriptionService transcriptionService;
    private RecordingAudioUploadCompletionService service;

    @BeforeEach
    void setUp() {
        completionTransaction = mock(RecordingAudioUploadCompletionTransaction.class);
        storage = mock(RecordingAudioStorage.class);
        transcriptionService = mock(RecordingTranscriptionService.class);
        service = new RecordingAudioUploadCompletionService(
                completionTransaction,
                storage,
                transcriptionService
        );
    }

    @Test
    @DisplayName("업로드 완료 사전 검증의 거절 원인을 기록하고 저장소 확인을 시도하지 않는다")
    void complete_prevalidationRejected() {
        // given
        when(findStorageKey()).thenThrow(new RecordingException(RecordingErrorCode.RECORDING_NOT_ENDED));
        try (LogCapture logs = new LogCapture(RecordingLog.class)) {
            // when
            Throwable failure = catchThrowable(
                    () -> service.complete(
                            WORKSPACE_ID,
                            MEMBER_ID,
                            RECORDING_ID,
                            UPLOAD_ID
                    )
            );

            // then
            assertThat(failure).isInstanceOf(RecordingException.class);
            assertThat(logs.events()).filteredOn(
                    event -> has(
                            event,
                            "outcome",
                            "rejected"
                    )
            )
                    .singleElement()
                    .satisfies(event -> {
                        assertThat(
                                has(
                                        event,
                                        "event",
                                        "recording.upload.prepare"
                                )
                        ).isTrue();
                        assertThat(
                                has(
                                        event,
                                        "errorCode",
                                        "RECORDING_NOT_ENDED"
                                )
                        ).isTrue();
                        assertThat(
                                has(
                                        event,
                                        "uploadId",
                                        UPLOAD_ID
                                )
                        ).isTrue();
                        assertThat(event.getLevel()).isEqualTo(Level.INFO);
                    });
            assertThat(logs.events()).noneMatch(
                    event -> has(
                            event,
                            "event",
                            "recording.upload.verify"
                    )
            );
            verifyNoInteractions(
                    storage,
                    transcriptionService
            );
        }
    }

    @Test
    @DisplayName("완료 재요청은 replayed로 기록하고 실제 HEAD를 수행한 것처럼 기록하지 않는다")
    void complete_replayedSkipsVerification() {
        // given
        when(findStorageKey()).thenReturn(Optional.empty());
        when(
                completionTransaction.complete(
                        WORKSPACE_ID,
                        MEMBER_ID,
                        RECORDING_ID,
                        UPLOAD_ID,
                        StoredAudioObject.missing()
                )
        ).thenReturn(
                new RecordingAudioUploadCompletionResult(
                        RECORDING_ID,
                        UPLOAD_ID,
                        RecordingAudioUploadStatus.COMPLETED,
                        Instant.now()
                )
        );
        try (LogCapture logs = new LogCapture(RecordingLog.class)) {
            // when
            service.complete(
                    WORKSPACE_ID,
                    MEMBER_ID,
                    RECORDING_ID,
                    UPLOAD_ID
            );

            // then
            assertThat(logs.events()).anyMatch(
                    event -> has(
                            event,
                            "outcome",
                            "replayed"
                    )
            );
            assertThat(logs.events()).noneMatch(
                    event -> has(
                            event,
                            "event",
                            "recording.upload.verify"
                    )
            );
            verifyNoInteractions(storage);
        }
    }

    @Test
    @DisplayName("저장소의 객체 부재는 확인 결과 missing이며 전송이나 DB 저장 성공으로 기록하지 않는다")
    void complete_objectMissing() {
        // given
        when(findStorageKey()).thenReturn(Optional.of(STORAGE_KEY));
        when(storage.findStoredObject(STORAGE_KEY)).thenReturn(StoredAudioObject.missing());
        when(
                completionTransaction.complete(
                        WORKSPACE_ID,
                        MEMBER_ID,
                        RECORDING_ID,
                        UPLOAD_ID,
                        StoredAudioObject.missing()
                )
        ).thenThrow(new RecordingException(RecordingErrorCode.AUDIO_UPLOAD_NOT_COMPLETED));
        try (LogCapture logs = new LogCapture(RecordingLog.class)) {
            // when
            Throwable failure = catchThrowable(
                    () -> service.complete(
                            WORKSPACE_ID,
                            MEMBER_ID,
                            RECORDING_ID,
                            UPLOAD_ID
                    )
            );

            // then
            assertThat(failure).isInstanceOf(RecordingException.class);
            assertThat(logs.events()).filteredOn(
                    event -> has(
                            event,
                            "outcome",
                            "missing"
                    )
            )
                    .singleElement()
                    .satisfies(event -> {
                        assertThat(
                                has(
                                        event,
                                        "event",
                                        "recording.upload.verify"
                                )
                        ).isTrue();
                        assertThat(
                                has(
                                        event,
                                        "uploadId",
                                        UPLOAD_ID
                                )
                        ).isTrue();
                    });
            assertThat(logs.events()).noneMatch(
                    event -> has(
                            event,
                            "outcome",
                            "completed"
                    )
            );
            assertThat(logs.events())
                    .allSatisfy(event -> assertThat(event.getFormattedMessage()).doesNotContain(STORAGE_KEY));
        }
    }

    private Optional<String> findStorageKey() {
        return completionTransaction.findStorageKeyToConfirm(
                WORKSPACE_ID,
                MEMBER_ID,
                RECORDING_ID,
                UPLOAD_ID
        );
    }

    private boolean has(
            ILoggingEvent event,
            String key,
            Object value
    ) {
        return event.getKeyValuePairs() != null && event.getKeyValuePairs()
                .stream()
                .anyMatch(
                        pair -> pair.key.equals(key) && String.valueOf(pair.value)
                                .equals(String.valueOf(value))
                );
    }
}
