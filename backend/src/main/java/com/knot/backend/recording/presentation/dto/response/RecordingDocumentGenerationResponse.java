package com.knot.backend.recording.presentation.dto.response;

import com.knot.backend.document.application.dto.result.DocumentGenerationSummaryResult;
import com.knot.backend.document.domain.model.DocumentGenerationProcessingStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

public record RecordingDocumentGenerationResponse(
        @Schema(description = "분류·문서 생성의 종합 상태") DocumentGenerationProcessingStatus status,
        @Schema(description = "생성에 성공한 문서 수") int succeededCount,
        @Schema(description = "실패 작업 ID. 보존 기한 없이 분류 실패를 포함한 모든 실패 작업이며 없으면 빈 배열") List<Long> failedJobIds
) {

    public static RecordingDocumentGenerationResponse from(DocumentGenerationSummaryResult result) {
        return new RecordingDocumentGenerationResponse(
                result.status(),
                result.succeededCount(),
                result.failedJobIds()
        );
    }
}
