package com.knot.backend.recording.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import com.knot.backend.recording.application.dto.result.RecordingAudioUploadCompletionResult;
import com.knot.backend.recording.domain.model.RecordingAudioUploadStatus;
import com.knot.backend.recording.domain.model.StoredAudioObject;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

class RecordingAudioUploadCompletionServiceTest {
    private static final long WORKSPACE_ID = 10L;
    private static final long MEMBER_ID = 1L;
    private static final long RECORDING_ID = 7L;
    private static final long UPLOAD_ID = 30L;
    private static final String STORAGE_KEY = "recordings/7/key";

    private RecordingAudioUploadService recordingAudioUploadService;
    private RecordingAudioStorage recordingAudioStorage;
    private RecordingAudioUploadCompletionService service;
    private RecordingTranscriptionService recordingTranscriptionService;

    @BeforeEach
    void setUp() {
        recordingAudioUploadService = mock(RecordingAudioUploadService.class);
        recordingAudioStorage = mock(RecordingAudioStorage.class);
        recordingTranscriptionService = mock(RecordingTranscriptionService.class);
        service = new RecordingAudioUploadCompletionService(
                recordingAudioUploadService,
                recordingAudioStorage,
                recordingTranscriptionService
        );
    }

    @Test
    @DisplayName("완료 전 예약은 검증 트랜잭션 뒤 저장소를 확인하고 그 결과로 완료 트랜잭션을 실행한다")
    void complete_success_checksStorageBetweenTransactions() {
        // given
        StoredAudioObject storedObject = StoredAudioObject.of(
                1024L,
                "audio/webm",
                "version-1"
        );
        when(findStorageKeyToConfirm()).thenReturn(Optional.of(STORAGE_KEY));
        when(recordingAudioStorage.findStoredObject(STORAGE_KEY)).thenReturn(storedObject);
        when(complete(storedObject)).thenReturn(completedResult());

        // when
        RecordingAudioUploadCompletionResult result = service.complete(
                WORKSPACE_ID,
                MEMBER_ID,
                RECORDING_ID,
                UPLOAD_ID
        );

        // then
        assertThat(result.uploadStatus()).isEqualTo(RecordingAudioUploadStatus.COMPLETED);
        InOrder inOrder = inOrder(
                recordingAudioUploadService,
                recordingAudioStorage,
                recordingTranscriptionService
        );
        inOrder.verify(recordingAudioUploadService)
                .findStorageKeyToConfirm(
                        WORKSPACE_ID,
                        MEMBER_ID,
                        RECORDING_ID,
                        UPLOAD_ID
                );
        inOrder.verify(recordingAudioStorage)
                .findStoredObject(STORAGE_KEY);
        inOrder.verify(recordingAudioUploadService)
                .complete(
                        WORKSPACE_ID,
                        MEMBER_ID,
                        RECORDING_ID,
                        UPLOAD_ID,
                        storedObject
                );
        inOrder.verify(recordingTranscriptionService)
                .dispatch();
    }

    @Test
    @DisplayName("이미 완료된 예약은 저장소를 다시 확인하지 않고 기존 결과를 반환한다")
    void complete_success_alreadyCompletedSkipsStorage() {
        // given
        when(findStorageKeyToConfirm()).thenReturn(Optional.empty());
        when(complete(StoredAudioObject.missing())).thenReturn(completedResult());

        // when
        RecordingAudioUploadCompletionResult result = service.complete(
                WORKSPACE_ID,
                MEMBER_ID,
                RECORDING_ID,
                UPLOAD_ID
        );

        // then
        assertThat(result.uploadId()).isEqualTo(UPLOAD_ID);
        verify(
                recordingAudioStorage,
                never()
        ).findStoredObject(anyString());
    }

    private Optional<String> findStorageKeyToConfirm() {
        return recordingAudioUploadService.findStorageKeyToConfirm(
                WORKSPACE_ID,
                MEMBER_ID,
                RECORDING_ID,
                UPLOAD_ID
        );
    }

    private RecordingAudioUploadCompletionResult complete(StoredAudioObject storedObject) {
        return recordingAudioUploadService.complete(
                WORKSPACE_ID,
                MEMBER_ID,
                RECORDING_ID,
                UPLOAD_ID,
                storedObject
        );
    }

    private RecordingAudioUploadCompletionResult completedResult() {
        return new RecordingAudioUploadCompletionResult(
                RECORDING_ID,
                UPLOAD_ID,
                RecordingAudioUploadStatus.COMPLETED,
                Instant.parse("2026-10-05T01:00:00Z")
        );
    }
}
