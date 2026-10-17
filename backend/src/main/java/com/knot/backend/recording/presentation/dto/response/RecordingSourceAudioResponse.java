package com.knot.backend.recording.presentation.dto.response;

import com.knot.backend.recording.application.dto.result.RecordingSourceAudioQuality;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

public record RecordingSourceAudioResponse(
        @Schema(description = "COMPLETE 또는 PARTIAL") String quality,
        @Schema(description = "알려진 누락 프레임 구간") List<FrameRange> missingRanges,
        @Schema(description = "클라이언트 종료 명세가 없어 마지막 길이를 알 수 없는지") boolean tailUnknown
) {
    public static RecordingSourceAudioResponse from(RecordingSourceAudioQuality source) {
        if (source == null) {
            return null;
        }
        return new RecordingSourceAudioResponse(
                source.quality(),
                source.missingRanges()
                        .stream()
                        .map(
                                range -> new FrameRange(
                                        range.start(),
                                        range.endExclusive()
                                )
                        )
                        .toList(),
                source.tailUnknown()
        );
    }

    public record FrameRange(
            long start,
            long endExclusive
    ) {
    }
}
