package com.knot.backend.recording.application.dto.result;

import com.knot.backend.recording.domain.model.MeetingSummaryStatus;

public record MeetingSummaryRetryResult(
        long recordingId,
        MeetingSummaryStatus status
) {
}
