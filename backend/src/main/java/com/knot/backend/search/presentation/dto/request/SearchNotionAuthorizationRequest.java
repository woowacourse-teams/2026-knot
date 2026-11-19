package com.knot.backend.search.presentation.dto.request;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;

public record SearchNotionAuthorizationRequest(
        @NotNull(message = "안내 동의 여부는 필수입니다") @AssertTrue(message = "안내에 동의해야 노션을 연결할 수 있습니다") @Schema(description = "노션에서 볼 수 있는 내용이 Workspace 멤버 모두에게 보일 수 있다는 안내에 동의했는지. true만 받습니다", requiredMode = REQUIRED, example = "true") Boolean agreed
) {
}
