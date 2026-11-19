package com.knot.backend.search.presentation.dto.response;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.knot.backend.search.application.dto.result.SearchNotionEvidenceItemResult;
import io.swagger.v3.oas.annotations.media.Schema;

public record SearchNotionEvidenceItemResponse(
        @Schema(description = "노션 페이지 제목", requiredMode = REQUIRED) String title,
        @Schema(description = "노션 페이지 주소", requiredMode = REQUIRED) String url
) {

    public static SearchNotionEvidenceItemResponse from(SearchNotionEvidenceItemResult result) {
        return new SearchNotionEvidenceItemResponse(
                result.title(),
                result.url()
        );
    }
}
