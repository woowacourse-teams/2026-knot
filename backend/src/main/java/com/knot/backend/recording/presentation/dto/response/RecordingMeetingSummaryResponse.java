package com.knot.backend.recording.presentation.dto.response;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.knot.backend.recording.application.dto.result.RecordingMeetingSummaryResult;
import com.knot.backend.recording.domain.model.MeetingSummaryStatus;
import io.swagger.v3.oas.annotations.media.Schema;

public record RecordingMeetingSummaryResponse(
        @Schema(description = "QUEUED·RUNNING은 화면의 '만드는 중'이다", example = "SUCCEEDED", requiredMode = REQUIRED) MeetingSummaryStatus status,
        @Schema(description = "읽기 전용 요약 본문. SUCCEEDED일 때만 값이 있고 나머지는 null", example = "탈퇴 시 데이터 보존 범위를 논의했다.", nullable = true, requiredMode = REQUIRED) String content,
        @Schema(description = "FAILED이고 입력 전사를 쓸 수 있어 지금 요약 재시도를 접수할 수 있으면 true", example = "false", requiredMode = REQUIRED) boolean retryable
) {

    public static RecordingMeetingSummaryResponse from(RecordingMeetingSummaryResult result) {
        if (result == null) {
            return null;
        }
        return new RecordingMeetingSummaryResponse(
                result.status(),
                result.content(),
                result.retryable()
        );
    }
}
