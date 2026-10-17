package com.knot.backend.recording.presentation.dto.response;

import com.knot.backend.recording.application.dto.result.RecordingTranscriptionRetryResult;
import com.knot.backend.recording.domain.model.TranscriptionStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

public record RecordingTranscriptionRetryResponse(
        @Schema(description = "녹음 세션 ID") long recordingId,
        @Schema(description = "접수 또는 복구한 전사 시도 ID") UUID attemptId,
        @Schema(description = "접수된 전사 상태. QUEUED는 실행을 기다리는 상태이며 실패가 아니다") TranscriptionStatus transcriptionStatus
) {

    public static RecordingTranscriptionRetryResponse from(RecordingTranscriptionRetryResult result) {
        return new RecordingTranscriptionRetryResponse(
                result.recordingId(),
                result.attemptId(),
                result.status()
        );
    }
}
