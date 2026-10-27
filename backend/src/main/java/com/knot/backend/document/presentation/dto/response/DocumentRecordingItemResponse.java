package com.knot.backend.document.presentation.dto.response;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.knot.backend.document.application.dto.result.DocumentRecordingItemResult;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;

public record DocumentRecordingItemResponse(
        @Schema(description = "녹음 세션 ID. 녹음 문서 조회에 쓴다", example = "512", requiredMode = REQUIRED) long recordingId,
        @Schema(description = "표시 제목. 최근 녹음 목록의 title과 같은 계산으로, 공개할 AI 녹음 제목이 없으면 `{녹음을 시작한 Member 닉네임} 님의 녹음`", example = "회원 탈퇴 정책 논의", requiredMode = REQUIRED) String title,
        @Schema(description = "녹음 시작 시각, UTC", example = "2026-10-22T01:30:00Z", requiredMode = REQUIRED) Instant startedAt,
        @Schema(description = "일시정지를 제외한 서버 누적 녹음 길이(초). 소수 초는 버린다", example = "1980", requiredMode = REQUIRED) int recordingDurationSeconds,
        @Schema(description = "이 녹음의 문서 수. documents 길이와 같고 1 이상", example = "2", requiredMode = REQUIRED) int documentCount,
        @Schema(description = "이 녹음의 Document 전부. 생성 시각 오름차순, 동률이면 ID 오름차순이며 나누지 않는다", requiredMode = REQUIRED) List<DocumentRecordingDocumentResponse> documents
) {

    public static DocumentRecordingItemResponse from(DocumentRecordingItemResult result) {
        List<DocumentRecordingDocumentResponse> documents = result.documents()
                .stream()
                .map(DocumentRecordingDocumentResponse::from)
                .toList();
        return new DocumentRecordingItemResponse(
                result.recordingId(),
                result.title(),
                result.startedAt(),
                result.recordingDurationSeconds(),
                documents.size(),
                documents
        );
    }
}
