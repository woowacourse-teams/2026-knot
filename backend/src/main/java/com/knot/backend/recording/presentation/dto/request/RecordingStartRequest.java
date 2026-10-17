package com.knot.backend.recording.presentation.dto.request;

import com.knot.backend.recording.application.dto.command.RecordingStartCommand;
import com.knot.backend.recording.domain.model.RecordingAudioMode;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.util.UUID;

// @formatter:off
public record RecordingStartRequest(
        @NotNull(message = "녹음 시작 요청 ID는 필수입니다")
        @Schema(description = "같은 시작 요청 재시도에 유지하는 UUID", requiredMode = Schema.RequiredMode.REQUIRED)
        UUID requestId,

        @NotNull(message = "녹음 탭 ID는 필수입니다")
        @Schema(description = "최초 녹음 탭에서 새로고침 동안 유지하는 UUID", requiredMode = Schema.RequiredMode.REQUIRED)
        UUID tabId,

        @NotNull(message = "녹음 제어 증명은 필수입니다")
        @Pattern(regexp = "[A-Za-z0-9_-]{42}[AEIMQUYcgkosw048]", message = "녹음 제어 증명 형식이 올바르지 않습니다")
        @Schema(description = "클라이언트의 32바이트 난수. 패딩 없는 Base64URL. 재시도·같은 탭 복구에 유지",
                accessMode = Schema.AccessMode.WRITE_ONLY, requiredMode = Schema.RequiredMode.REQUIRED)
        String controlToken,

        @Schema(description = "오디오 저장 방식. 생략하면 FINAL_WEBM_V1", defaultValue = "FINAL_WEBM_V1")
        RecordingAudioMode audioMode
) {
    // @formatter:on

    public RecordingStartRequest {
        audioMode = java.util.Objects.requireNonNullElse(
                audioMode,
                RecordingAudioMode.FINAL_WEBM_V1
        );
    }

    public RecordingStartRequest(
            UUID requestId,
            UUID tabId,
            String controlToken
    ) {
        this(
                requestId,
                tabId,
                controlToken,
                RecordingAudioMode.FINAL_WEBM_V1
        );
    }

    public RecordingStartCommand toCommand() {
        return new RecordingStartCommand(
                requestId,
                tabId,
                controlToken,
                audioMode
        );
    }

    @Override
    public String toString() {
        return "RecordingStartRequest[controlToken=REDACTED]";
    }
}
