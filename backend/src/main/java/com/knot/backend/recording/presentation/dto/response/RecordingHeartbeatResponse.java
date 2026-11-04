package com.knot.backend.recording.presentation.dto.response;

import com.knot.backend.recording.application.dto.result.RecordingHeartbeatResult;
import com.knot.backend.recording.domain.model.RecordingConnectionStatus;
import com.knot.backend.recording.domain.model.RecordingEndReason;
import com.knot.backend.recording.domain.model.RecordingStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

public record RecordingHeartbeatResponse(
        @Schema(description = "녹음 세션 ID") long recordingId,
        @Schema(description = "녹음 상태") RecordingStatus status,
        @Schema(description = "일시정지를 뺀 서버 기준 녹음 시간") long elapsedMillis,
        @Schema(description = "서버가 녹음 탭의 생존 신호를 마지막으로 접수한 시각. 끝난 녹음은 끝난 시각 이하") Instant lastSeenAt,
        @Schema(description = "생존 신호가 없으면 연결 만료로 끝나는 시각(lastSeenAt + 끊김 유예, 기본 30분). 끝난 녹음은 null", nullable = true) Instant expiresAt,
        @Schema(description = "끝난 시각. 진행 중이면 null. 연결 만료면 마지막 생존 시각", nullable = true) Instant endedAt,
        @Schema(description = "종료 사유. 진행 중이거나 기록 전 종료면 null", nullable = true) RecordingEndReason endReason,
        @Schema(description = "녹음 탭 연결 상태. serverNow가 lastSeenAt 뒤 끊김 표시 시간(기본 30초) 이상이면 DISCONNECTED. 끝난 녹음은 null", nullable = true) RecordingConnectionStatus connectionStatus,
        @Schema(description = "응답을 만든 서버 시각") Instant serverNow
) {

    public static RecordingHeartbeatResponse from(RecordingHeartbeatResult result) {
        return new RecordingHeartbeatResponse(
                result.recordingId(),
                result.status(),
                result.elapsedMillis(),
                result.lastSeenAt(),
                result.expiresAt(),
                result.endedAt(),
                result.endReason(),
                result.connectionStatus(),
                result.serverNow()
        );
    }
}
