package com.knot.backend.recording.presentation.dto.response;

import com.knot.backend.recording.application.dto.result.TeammateRecordingResult;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

public record TeammateRecordingListResponse(
        @Schema(description = "팀원의 녹음 중·일시정지 녹음. 시작 시각, 녹음 ID 내림차순이며 없으면 빈 배열") List<TeammateRecordingResponse> items
) {

    public static TeammateRecordingListResponse from(List<TeammateRecordingResult> results) {
        return new TeammateRecordingListResponse(
                results.stream()
                        .map(TeammateRecordingResponse::from)
                        .toList()
        );
    }
}
