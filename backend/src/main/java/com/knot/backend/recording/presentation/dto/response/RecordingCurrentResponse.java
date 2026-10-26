package com.knot.backend.recording.presentation.dto.response;

import com.knot.backend.recording.application.dto.result.RecordingCurrentResult;
import com.knot.backend.recording.application.dto.result.CurrentRecordingStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;

public record RecordingCurrentResponse(
        @Schema(description = "녹음 세션 ID") long recordingId,
        @Schema(description = "녹음 중·일시정지·처리 중·전사 실패·문서 생성 실패 상태") CurrentRecordingStatus status,
        @Schema(description = "서버가 확정한 녹음 시작 시각") Instant startedAt,
        @Schema(description = "누적 녹음 시간(ms). 일시정지 구간은 제외하고 종료된 녹음은 고정") long elapsedMillis,
        @Schema(description = "현재 실패를 다시 접수할 수 있는지. 활성·처리 중은 false") boolean retryable,
        @Schema(description = "문서 실패에서 표시 기한 안의 실패 Job 중 저장 원문을 사용할 수 있는 Job ID. 횟수·경과 시간 제한 없음. 그 밖의 상태는 빈 배열") List<Long> retryableJobIds
) {

    public static RecordingCurrentResponse from(RecordingCurrentResult result) {
        return new RecordingCurrentResponse(
                result.recordingId(),
                result.status(),
                result.startedAt(),
                result.elapsedMillis(),
                result.retryable(),
                result.retryableJobIds()
        );
    }
}
