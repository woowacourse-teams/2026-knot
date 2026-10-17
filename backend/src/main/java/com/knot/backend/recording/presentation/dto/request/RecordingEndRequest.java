package com.knot.backend.recording.presentation.dto.request;

import com.knot.backend.recording.application.dto.command.RecordingAudioSealCommand;
import com.knot.backend.recording.application.dto.command.RecordingControlCommand;
import com.knot.backend.recording.domain.RecordingErrorCode;
import com.knot.backend.recording.domain.RecordingException;
import com.knot.backend.recording.domain.model.AudioChunkDescriptor;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.UUID;

// @formatter:off
public record RecordingEndRequest(
        @NotNull(message = "녹음 탭 ID는 필수입니다")
        UUID tabId,

        @NotNull(message = "녹음 제어 증명은 필수입니다")
        @Pattern(regexp = "[A-Za-z0-9_-]{42}[AEIMQUYcgkosw048]", message = "녹음 제어 증명 형식이 올바르지 않습니다")
        @Schema(accessMode = Schema.AccessMode.WRITE_ONLY)
        String controlToken,

        @Valid
        AudioSeal audioSeal
) {
    // @formatter:on

    public RecordingControlCommand toCommand() {
        if (tabId == null || controlToken == null || controlToken.isBlank()) {
            throw new RecordingException(RecordingErrorCode.INVALID_RECORDING_DATA);
        }
        return new RecordingControlCommand(
                tabId,
                controlToken
        );
    }

    public RecordingAudioSealCommand toSeal() {
        if (audioSeal == null) {
            return null;
        }
        return new RecordingAudioSealCommand(
                audioSeal.lastSequence(),
                audioSeal.totalFrameCount(),
                audioSeal.pendingDescriptors()
        );
    }

    @Override
    public String toString() {
        return "RecordingEndRequest[인증 정보 생략]";
    }

    public record AudioSeal(
            int lastSequence,
            long totalFrameCount,
            List<AudioChunkDescriptor> pendingDescriptors
    ) {
    }
}
