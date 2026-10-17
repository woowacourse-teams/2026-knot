package com.knot.backend.recording.presentation.dto.response;

import com.knot.backend.recording.application.dto.result.RecordingDetailResult;
import com.knot.backend.recording.domain.model.RecordingAudioUploadStatus;
import com.knot.backend.recording.domain.model.RecordingEndReason;
import com.knot.backend.recording.domain.model.RecordingStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import com.knot.backend.recording.domain.model.TranscriptionStatus;
import java.util.UUID;
import java.time.Instant;

public record RecordingDetailResponse(
        @Schema(description = "녹음 세션 상태") RecordingStatus status,
        @Schema(description = "일시정지를 뺀 서버 기준 녹음 시간") long elapsedMillis,
        @Schema(description = "종료 시각. 진행 중이면 null", nullable = true) Instant endedAt,
        @Schema(description = "종료 사유. 진행 중이거나 기록 전 종료면 null", nullable = true) RecordingEndReason endReason,
        @Schema(description = "최종 오디오 업로드 상태. 예약 전 null", nullable = true) RecordingAudioUploadStatus audioUploadStatus,
        @Schema(description = "현재 전사 시도 ID. 접수 전 null", nullable = true) UUID transcriptionAttemptId,
        @Schema(description = "전사 상태. 접수 전 null", nullable = true) TranscriptionStatus transcriptionStatus,
        @Schema(description = "전사 실패 코드. 실패가 아니면 null", nullable = true) String transcriptionFailureCode,

        @Schema(description = "문서 생성 결과. 접수 전 null", types = {
                "object", "null"}) RecordingDocumentGenerationResponse documentGeneration,
        @Schema(description = "원본 오디오 품질·누락. 기존 녹음은 null", nullable = true) RecordingSourceAudioResponse sourceAudio
) {

    public static RecordingDetailResponse from(RecordingDetailResult result) {
        return new RecordingDetailResponse(
                result.status(),
                result.elapsedMillis(),
                result.endedAt(),
                result.endReason(),
                result.audioUploadStatus(),
                result.transcriptionAttemptId(),
                result.transcriptionStatus(),
                result.transcriptionFailureCode(),
                result.documentGeneration() == null
                        ? null
                        : RecordingDocumentGenerationResponse.from(result.documentGeneration()),
                RecordingSourceAudioResponse.from(result.sourceAudio())
        );
    }
}
