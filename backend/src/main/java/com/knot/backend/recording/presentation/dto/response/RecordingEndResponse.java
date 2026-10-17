package com.knot.backend.recording.presentation.dto.response;

import com.knot.backend.recording.application.dto.result.RecordingEndResult;
import com.knot.backend.recording.application.dto.result.RecordingAudioSealSummary;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.knot.backend.recording.domain.model.RecordingStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

public record RecordingEndResponse(
        @Schema(description = "녹음 세션 ID") long recordingId,
        @Schema(description = "항상 ENDED. 업로드·전사 처리 상태와 구분한다") RecordingStatus status,
        @Schema(description = "서버가 확정한 종료 시각. 반복 종료에도 바뀌지 않는다") Instant endedAt,
        @JsonInclude(JsonInclude.Include.NON_NULL) RecordingAudioSealSummary audioSave
) {

    public static RecordingEndResponse from(RecordingEndResult result) {
        return new RecordingEndResponse(
                result.recordingId(),
                result.status(),
                result.endedAt(),
                result.audioSave()
        );
    }
}
