package com.knot.backend.recording.application.dto.result;

import com.knot.backend.recording.domain.model.MeetingSummaryStatus;
import com.knot.backend.recording.domain.model.RecordingMeetingSummary;

public record RecordingMeetingSummaryResult(
        MeetingSummaryStatus status,
        String content,
        boolean retryable
) {

    // 본문은 SUCCEEDED일 때만 저장된다. retryable은 요약 재시도 접수 조건과 같다.
    public static RecordingMeetingSummaryResult of(
            RecordingMeetingSummary summary,
            boolean usableInput
    ) {
        return new RecordingMeetingSummaryResult(
                summary.getStatus(),
                summary.getContent(),
                summary.canRetry(usableInput)
        );
    }
}
