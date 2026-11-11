package com.knot.backend.document.presentation.dto.response;

import com.knot.backend.recording.application.dto.result.TranscriptSegmentResult;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;

public record DocumentTranscriptSegmentResponse(
        @Schema(description = "최종 오디오 기준 실제 발언 시작 위치(ms)", minimum = "0", requiredMode = RequiredMode.REQUIRED) long startMillis,
        @Schema(description = "발언 종료 위치(ms). 미상이면 null, 값이 있으면 startMillis 이상", nullable = true) Long endMillis,
        @Schema(description = "동일 원문 내 익명 화자 번호. 미상이면 null", minimum = "1", nullable = true) Integer speakerNumber,
        @Schema(description = "읽기 전용 발언 문장", requiredMode = RequiredMode.REQUIRED) String text,
        @Schema(description = "녹음에서 수정한 화자 표시명. null이면 참여자 N", nullable = true) String speakerDisplayName
) {

    public static DocumentTranscriptSegmentResponse from(TranscriptSegmentResult result) {
        return new DocumentTranscriptSegmentResponse(
                result.startMillis(),
                result.endMillis(),
                result.speakerNumber(),
                result.text(),
                result.speakerDisplayName()
        );
    }
}
