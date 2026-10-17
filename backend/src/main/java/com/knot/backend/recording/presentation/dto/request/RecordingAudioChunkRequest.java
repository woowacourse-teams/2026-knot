package com.knot.backend.recording.presentation.dto.request;

import com.knot.backend.recording.application.dto.command.RecordingAudioChunkCommand;
import com.knot.backend.recording.application.dto.command.RecordingControlCommand;
import com.knot.backend.recording.domain.model.AudioChunkDescriptor;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.util.UUID;

// @formatter:off
public record RecordingAudioChunkRequest(
        @NotNull(message = "녹음 탭 ID는 필수입니다")
        UUID tabId,

        @NotNull(message = "녹음 제어 증명은 필수입니다")
        @Pattern(regexp = "[A-Za-z0-9_-]{42}[AEIMQUYcgkosw048]", message = "녹음 제어 증명 형식이 올바르지 않습니다")
        @Schema(accessMode = Schema.AccessMode.WRITE_ONLY)
        String controlToken,

        @NotNull(message = "오디오 조각 번호는 필수입니다")
        @Schema(description = "1부터 연속 증가하는 조각 번호", minimum = "1")
        Integer sequence,

        @NotNull(message = "오디오 시작 프레임은 필수입니다")
        @Schema(description = "앞 조각들의 frameCount 합", minimum = "0")
        Long sampleOffset,

        @NotNull(message = "오디오 프레임 수는 필수입니다")
        @Schema(minimum = "1", maximum = "80000")
        Integer frameCount,

        @NotNull(message = "오디오 바이트 수는 필수입니다")
        @Schema(description = "frameCount의 두 배")
        Long byteLength,

        @NotNull(message = "오디오 체크섬은 필수입니다")
        @Schema(description = "봉인된 PCM bytes의 SHA-256을 정규 Base64로 인코딩한 값")
        String checksumSha256
) {
    // @formatter:on

    public RecordingAudioChunkCommand toCommand() {
        return new RecordingAudioChunkCommand(
                new RecordingControlCommand(
                        tabId,
                        controlToken
                ),
                new AudioChunkDescriptor(
                        sequence,
                        sampleOffset,
                        frameCount,
                        byteLength,
                        checksumSha256
                )
        );
    }

    @Override
    public String toString() {
        return "RecordingAudioChunkRequest[sequence=" + sequence + ", controlToken=REDACTED]";
    }
}
