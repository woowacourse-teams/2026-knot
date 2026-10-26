package com.knot.backend.recording.presentation.dto.response;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.knot.backend.recording.application.dto.result.RecentRecordingListResult;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

public record RecentRecordingListResponse(
        @Schema(description = "시작 시각 내림차순, 동률이면 녹음 ID 내림차순인 녹음. 없으면 빈 배열", requiredMode = REQUIRED) List<RecentRecordingItemResponse> items,
        @Schema(description = "다음 페이지 커서. 마지막 페이지이면 null", nullable = true, requiredMode = REQUIRED) String nextCursor
) {

    public static RecentRecordingListResponse from(RecentRecordingListResult result) {
        return new RecentRecordingListResponse(
                result.items()
                        .stream()
                        .map(RecentRecordingItemResponse::from)
                        .toList(),
                result.nextCursor()
        );
    }
}
