package com.knot.backend.recording.presentation.dto.response;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.knot.backend.recording.application.dto.result.RecordingDocumentProcessingStatus;
import com.knot.backend.recording.application.dto.result.RecordingDocumentResult;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;

public record RecordingDocumentResponse(
        @Schema(description = "녹음 세션 ID", example = "512", requiredMode = REQUIRED) long recordingId,
        @Schema(description = "표시 제목. 최근 녹음 목록의 title과 같은 규칙이며 공개할 AI 녹음 제목이 없으면 '{녹음을 시작한 Member 닉네임} 님의 녹음'", example = "회원 탈퇴 정책 논의", requiredMode = REQUIRED) String title,
        @Schema(description = "녹음 시작 시각, UTC ISO 8601", example = "2026-10-22T01:30:00Z", requiredMode = REQUIRED) Instant startedAt,
        @Schema(description = "일시정지를 제외한 서버 누적 녹음 길이(초, 소수 초 버림)", example = "1980", requiredMode = REQUIRED) int recordingDurationSeconds,
        @Schema(description = "응답용 처리 상태. 대기·실행 중인 음성 최종화·전사·주제별 문서 작업이 있으면 실패보다 PROCESSING이 먼저다. 회의 전체 요약의 진행은 넣지 않는다", example = "DOCUMENT_FAILED", requiredMode = REQUIRED) RecordingDocumentProcessingStatus processingStatus,
        @Schema(description = "처리 실패를 지금 다시 접수할 수 있는지. TRANSCRIPTION_FAILED는 전사 재시도 접수 조건, DOCUMENT_FAILED는 retryableJobIds가 비어 있지 않은지. 나머지는 false", example = "true", requiredMode = REQUIRED) boolean retryable,
        @Schema(description = "DOCUMENT_FAILED에서 지금 접수할 수 있는 FAILED 문서 생성 Job ID. 홈 진행 카드의 7일 표시 기한과 무관하다. 나머지 상태는 빈 배열", example = "[88, 91]", requiredMode = REQUIRED) List<Long> retryableJobIds,
        @Schema(description = "회의 전체 요약. 성공 Transcript가 저장되고 NO_CONTENT가 아님이 정해진 녹음에서 요약이 접수됐을 때만 객체이고, 그 밖과 요약 기능을 켜기 전에 끝난 녹음은 null", nullable = true, requiredMode = REQUIRED) RecordingMeetingSummaryResponse meetingSummary
) {

    public RecordingDocumentResponse {
        retryableJobIds = List.copyOf(retryableJobIds);
    }

    public static RecordingDocumentResponse from(RecordingDocumentResult result) {
        return new RecordingDocumentResponse(
                result.recordingId(),
                result.title(),
                result.startedAt(),
                result.recordingDurationSeconds(),
                result.processingStatus(),
                result.retryable(),
                result.retryableJobIds(),
                RecordingMeetingSummaryResponse.from(result.meetingSummary())
        );
    }
}
