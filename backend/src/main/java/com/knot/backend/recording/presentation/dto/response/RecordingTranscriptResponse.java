package com.knot.backend.recording.presentation.dto.response;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.knot.backend.document.presentation.dto.response.DocumentTranscriptSegmentResponse;
import com.knot.backend.recording.application.dto.result.RecordingTranscriptResult;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

public record RecordingTranscriptResponse(
        @Schema(description = "녹음 세션 ID", example = "512", requiredMode = REQUIRED) long recordingId,
        @Schema(description = "저장된 전사 원문 ID. 같은 녹음에서 나온 주제별 문서의 원문 조회 transcriptId와 같다", example = "81", requiredMode = REQUIRED) long transcriptId,
        @Schema(description = "저장된 전체 원문", example = "지난주에 이야기한 탈퇴 정책부터 정리할게요.", requiredMode = REQUIRED) String transcriptText,
        @ArraySchema(schema = @Schema(implementation = DocumentTranscriptSegmentResponse.class)) @Schema(description = "실제 발화 구간. 시작 시각 오름차순, 동률이면 저장 순서. 문서 원문 조회와 같은 데이터·구조·정렬", requiredMode = REQUIRED) List<DocumentTranscriptSegmentResponse> segments
) {

    public RecordingTranscriptResponse {
        segments = List.copyOf(segments);
    }

    public static RecordingTranscriptResponse from(RecordingTranscriptResult result) {
        return new RecordingTranscriptResponse(
                result.recordingId(),
                result.transcriptId(),
                result.transcriptText(),
                result.segments()
                        .stream()
                        .map(DocumentTranscriptSegmentResponse::from)
                        .toList()
        );
    }
}
