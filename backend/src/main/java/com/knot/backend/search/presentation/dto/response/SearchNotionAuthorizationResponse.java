package com.knot.backend.search.presentation.dto.response;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;

public record SearchNotionAuthorizationResponse(
        @Schema(description = "노션 동의 화면 주소", requiredMode = REQUIRED) String authorizationUrl
) {
}
