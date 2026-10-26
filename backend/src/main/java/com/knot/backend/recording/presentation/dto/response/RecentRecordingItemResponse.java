package com.knot.backend.recording.presentation.dto.response;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.knot.backend.recording.application.dto.result.RecentRecordingRowResult;
import com.knot.backend.recording.application.dto.result.RecordingDocumentProcessingStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

public record RecentRecordingItemResponse(
        @Schema(description = "녹음 세션 ID. 녹음 문서 조회에 쓴다", example = "512", requiredMode = REQUIRED) long recordingId,
        @Schema(description = "표시 제목. 공개할 AI 녹음 제목이 있으면 그 제목, 없으면 `{녹음을 시작한 Member 닉네임} 님의 녹음`", example = "회원 탈퇴 정책 논의", requiredMode = REQUIRED) String title,
        @Schema(description = "녹음 시작 시각, UTC", example = "2026-10-22T01:30:00Z", requiredMode = REQUIRED) Instant startedAt,
        @Schema(description = "일시정지를 제외한 서버 누적 녹음 길이(초). 소수 초는 버린다", example = "1980", requiredMode = REQUIRED) int recordingDurationSeconds,
        @Schema(description = "녹음 문서 조회의 processingStatus와 같은 규칙의 처리 상태. 대기·실행 중인 음성 최종화·전사·주제별 문서 작업이 있으면 실패보다 PROCESSING이 먼저다. 회의 전체 요약의 진행은 넣지 않는다. 주제 분류 실패도 DOCUMENT_FAILED다", example = "DOCUMENT_FAILED", requiredMode = REQUIRED) RecordingDocumentProcessingStatus processingStatus,
        @Schema(description = "녹음 문서 조회의 retryable과 같은 값. 처리 실패를 지금 다시 접수할 수 있는지이며 다시 시도는 녹음 문서에서 한다. 나머지 상태는 false", example = "true", requiredMode = REQUIRED) boolean retryable
) {

    public static RecentRecordingItemResponse from(RecentRecordingRowResult result) {
        return new RecentRecordingItemResponse(
                result.recordingId(),
                result.title(),
                result.startedAt(),
                result.recordingDurationSeconds(),
                result.processingStatus(),
                result.retryable()
        );
    }
}
