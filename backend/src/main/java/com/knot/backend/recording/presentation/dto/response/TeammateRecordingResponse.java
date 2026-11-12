package com.knot.backend.recording.presentation.dto.response;

import com.knot.backend.recording.application.dto.result.CurrentRecordingStatus;
import com.knot.backend.recording.application.dto.result.TeammateRecordingResult;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

public record TeammateRecordingResponse(
        @Schema(description = "녹음 세션 ID") long recordingId,
        @Schema(description = "RECORDING 또는 PAUSED", allowableValues = {
                "RECORDING", "PAUSED"}) CurrentRecordingStatus status,
        @Schema(description = "녹음 이름. '{녹음 시작 Member 닉네임} 님의 녹음'") String title,
        @Schema(description = "서버가 확정한 녹음 시작 시각") Instant startedAt,
        @Schema(description = "응답 시각 기준 누적 녹음 시간(ms). 일시정지 구간은 제외") long elapsedMillis
) {

    public static TeammateRecordingResponse from(TeammateRecordingResult result) {
        return new TeammateRecordingResponse(
                result.recordingId(),
                result.status(),
                result.title(),
                result.startedAt(),
                result.elapsedMillis()
        );
    }
}
