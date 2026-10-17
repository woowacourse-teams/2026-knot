package com.knot.backend.document.presentation.dto.response;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.knot.backend.document.application.dto.result.DocumentDetailResult;
import com.knot.backend.document.domain.model.DocumentStatus;
import com.knot.backend.document.domain.model.MyConfirmationState;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import com.knot.backend.recording.presentation.dto.response.RecordingSourceAudioResponse;

public record DocumentDetailResponse(
        @Schema(description = "Document ID", requiredMode = REQUIRED) long id,
        @Schema(description = "원본 RecordingSession ID. 같은 녹음 문서 조회에 사용", requiredMode = REQUIRED) long recordingSessionId,
        @Schema(description = "AI가 분류한 주제 폴더 이름", requiredMode = REQUIRED) String topic,
        @Schema(description = "AI가 생성한 읽기 전용 제목", requiredMode = REQUIRED) String title,
        @Schema(description = "AI 요약. 없으면 null", nullable = true, requiredMode = REQUIRED) String summary,
        @Schema(description = "읽기 전용 Markdown 본문", requiredMode = REQUIRED) String content,
        @Schema(description = "문서 상태", requiredMode = REQUIRED) DocumentStatus status,
        @Schema(description = "문서 생성 시각, UTC", requiredMode = REQUIRED) Instant createdAt,
        @Schema(description = "보관 전환 시각, UTC. DRAFT이면 null", nullable = true, requiredMode = REQUIRED) Instant archivedAt,
        @Schema(description = "일시정지를 제외한 서버 누적 녹음 길이. 소수 초는 버림", requiredMode = REQUIRED) int recordingDurationSeconds,
        @Schema(description = "문서를 만든 Transcript ID", requiredMode = REQUIRED) long sourceTranscriptId,
        @Schema(description = "PENDING=대상 미확인, CONFIRMED=확인 완료, NOT_REQUIRED=비대상", requiredMode = REQUIRED) MyConfirmationState myConfirmationState,
        @Schema(description = "확인 대상 집계", requiredMode = REQUIRED) DocumentConfirmationSummaryResponse confirmationSummary,
        @Schema(description = "원본 오디오 품질·누락. 기존 녹음은 null", nullable = true) RecordingSourceAudioResponse sourceAudio
) {
    public static DocumentDetailResponse from(DocumentDetailResult result) {
        return new DocumentDetailResponse(
                result.id(),
                result.recordingSessionId(),
                result.topic(),
                result.title(),
                result.summary(),
                result.content(),
                result.status(),
                result.createdAt(),
                result.archivedAt(),
                result.recordingDurationSeconds(),
                result.sourceTranscriptId(),
                result.myConfirmationState(),
                DocumentConfirmationSummaryResponse.from(result.confirmationSummary()),
                RecordingSourceAudioResponse.from(result.sourceAudio())
        );
    }
}
