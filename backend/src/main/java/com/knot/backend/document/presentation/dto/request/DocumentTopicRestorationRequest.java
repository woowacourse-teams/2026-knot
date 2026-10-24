package com.knot.backend.document.presentation.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;

public record DocumentTopicRestorationRequest(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, format = "uuid", description = "한 번의 복원 의도. 자동 재전송에만 같은 UUID 사용") @NotNull @Pattern(regexp = "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}") String requestId,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "현재 상태 조회의 복원 가능한 버전 ID", example = "91") @NotNull @Positive Long versionId
) {
}
