package com.knot.backend.recording.domain;

public record TranscriptUtterance(
        String speakerLabel,
        double startSeconds,
        double endSeconds,
        String text
) {
    private static final double MILLIS_PER_SECOND = 1000;

    public TranscriptUtterance {
        if (!isValidRange(
                startSeconds,
                endSeconds
        )) {
            throw new RecordingException(RecordingErrorCode.INVALID_RECORDING_DATA);
        }
    }

    public long startMillis() {
        return Math.round(startSeconds * MILLIS_PER_SECOND);
    }

    public long endMillis() {
        return Math.round(endSeconds * MILLIS_PER_SECOND);
    }

    private static boolean isValidRange(
            double startSeconds,
            double endSeconds
    ) {
        return Double.isFinite(startSeconds) && Double.isFinite(endSeconds) && startSeconds >= 0
                && endSeconds >= startSeconds
                && endSeconds * MILLIS_PER_SECOND <= RecordingSession.MAX_RECORDING_MILLIS;
    }
}
