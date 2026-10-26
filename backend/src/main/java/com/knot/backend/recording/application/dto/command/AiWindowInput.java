package com.knot.backend.recording.application.dto.command;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.knot.backend.recording.domain.model.TranscriptionDecodePolicy;

/**
 * decodePolicy가 null인 30초 구간은 필드를 보내지 않아, 정책을 모르는 AI 서버에도 이전과 같은 요청이 된다.
 */
public record AiWindowInput(
        String pipelineVersion,
        String inputManifestSha256,
        long inputStartFrame,
        long inputEndFrame,
        @JsonInclude(JsonInclude.Include.NON_NULL) TranscriptionDecodePolicy decodePolicy
) {
}
