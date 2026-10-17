package com.knot.backend.recording.application.dto.result;

public record AudioFrameRange(
        long start,
        long endExclusive
) {
}
