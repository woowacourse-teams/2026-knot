package com.knot.backend.recording.presentation.dto.response;

import com.knot.backend.recording.application.dto.result.MeetingSummaryRetryResult;
import com.knot.backend.recording.domain.model.MeetingSummaryStatus;
import io.swagger.v3.oas.annotations.media.Schema;

public record MeetingSummaryRetryResponse(
        @Schema(description = "녹음 세션 ID") long recordingId,
        @Schema(description = "접수 당시 요약 상태. 항상 QUEUED") MeetingSummaryStatus meetingSummaryStatus
) {

    public static MeetingSummaryRetryResponse from(MeetingSummaryRetryResult result) {
        return new MeetingSummaryRetryResponse(
                result.recordingId(),
                result.status()
        );
    }
}
