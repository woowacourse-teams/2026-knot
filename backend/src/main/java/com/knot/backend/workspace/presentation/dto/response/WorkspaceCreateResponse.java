package com.knot.backend.workspace.presentation.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

public record WorkspaceCreateResponse(@Schema(description = "생성된 워크스페이스 ID", example = "1") long id) {
}
