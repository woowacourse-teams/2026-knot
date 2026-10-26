package com.knot.backend.document.presentation.dto.response;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.knot.backend.document.application.dto.result.DocumentRecordingListResult;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

public record DocumentRecordingListResponse(
        @Schema(description = "문서가 있는 녹음 묶음. 시작 시각 내림차순, 동률이면 녹음 ID 내림차순이며 최대 20개. 없으면 빈 배열", requiredMode = REQUIRED) List<DocumentRecordingItemResponse> items,
        @Schema(description = "다음 페이지 커서. 마지막 페이지이면 null", nullable = true, requiredMode = REQUIRED) String nextCursor
) {

    public static DocumentRecordingListResponse from(DocumentRecordingListResult result) {
        return new DocumentRecordingListResponse(
                result.items()
                        .stream()
                        .map(DocumentRecordingItemResponse::from)
                        .toList(),
                result.nextCursor()
        );
    }
}
