package com.knot.backend.search.presentation.dto.response;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.knot.backend.search.application.dto.result.SearchMessageListItemResult;
import com.knot.backend.search.application.dto.result.SearchDocumentSelection;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.knot.backend.search.domain.model.SearchMessageRole;
import com.knot.backend.search.domain.model.SearchMessageStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;

public record SearchMessageListItemResponse(
        @Schema(requiredMode = REQUIRED) long id,
        @Schema(requiredMode = REQUIRED) SearchMessageRole role,
        @Schema(minimum = "1", requiredMode = REQUIRED) int sequence,
        @Schema(description = "저장된 전체 본문. STREAMING은 빈 문자열 허용", requiredMode = REQUIRED) String content,
        @Schema(requiredMode = REQUIRED) SearchMessageStatus status,
        @Schema(requiredMode = REQUIRED) boolean retryable,
        @Schema(requiredMode = REQUIRED) Instant createdAt,
        @Schema(description = "답변에 실제 사용한 문서. rank 오름차순 최대 3개", requiredMode = REQUIRED) List<SearchEvidenceItemResponse> evidences,
        @JsonInclude(JsonInclude.Include.NON_NULL) SearchDocumentSelection documentSelection
) {

    public static SearchMessageListItemResponse from(SearchMessageListItemResult result) {
        return new SearchMessageListItemResponse(
                result.id(),
                result.role(),
                result.sequence(),
                result.content(),
                result.status(),
                result.retryable(),
                result.createdAt(),
                result.evidences()
                        .stream()
                        .map(SearchEvidenceItemResponse::from)
                        .toList(),
                result.documentSelection()
        );
    }
}
