package com.knot.backend.recording.presentation.dto.request;

import com.knot.backend.recording.application.dto.command.RecordingReconnectCommand;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.util.UUID;

// @formatter:off
public record RecordingReconnectRequest(
        @NotNull(message = "이전 녹음 탭 ID는 필수입니다")
        @Schema(description = "새로고침 전 녹음 탭의 UUID", requiredMode = Schema.RequiredMode.REQUIRED)
        UUID previousTabId,

        @NotNull(message = "이전 녹음 제어 증명은 필수입니다")
        @Pattern(regexp = "[A-Za-z0-9_-]{42}[AEIMQUYcgkosw048]", message = "이전 녹음 제어 증명 형식이 올바르지 않습니다")
        @Schema(description = "새로고침 전 녹음 탭의 제어 증명. 패딩 없는 Base64URL",
                accessMode = Schema.AccessMode.WRITE_ONLY, requiredMode = Schema.RequiredMode.REQUIRED)
        String previousControlToken,

        @NotNull(message = "녹음 탭 ID는 필수입니다")
        @Schema(description = "새로고침 뒤 새로 만든 탭 UUID. previousTabId와 달라야 한다", requiredMode = Schema.RequiredMode.REQUIRED)
        UUID tabId,

        @NotNull(message = "녹음 제어 증명은 필수입니다")
        @Pattern(regexp = "[A-Za-z0-9_-]{42}[AEIMQUYcgkosw048]", message = "녹음 제어 증명 형식이 올바르지 않습니다")
        @Schema(description = "새로 만든 제어 증명. 이전 증명과 달라야 하고 재시도에는 같은 값을 쓴다. 패딩 없는 Base64URL",
                accessMode = Schema.AccessMode.WRITE_ONLY, requiredMode = Schema.RequiredMode.REQUIRED)
        String controlToken
) {
    // @formatter:on

    public RecordingReconnectCommand toCommand() {
        return new RecordingReconnectCommand(
                previousTabId,
                previousControlToken,
                tabId,
                controlToken
        );
    }

    @Override
    public String toString() {
        return "RecordingReconnectRequest[previousControlToken=REDACTED, controlToken=REDACTED]";
    }
}
