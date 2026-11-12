package com.knot.backend.recording.domain.model;

import com.knot.backend.recording.domain.RecordingErrorCode;
import com.knot.backend.recording.domain.RecordingException;

public record SpeakerDisplayName(String value) {
    public SpeakerDisplayName {
        if (value == null || value.codePoints()
                .anyMatch(SpeakerDisplayName::forbidden)) {
            throw invalid();
        }
        int[] points = value.codePoints()
                .toArray();
        int start = 0;
        int end = points.length;
        while (start < end && whitespace(points[start])) {
            start++;
        }
        while (end > start && whitespace(points[end - 1])) {
            end--;
        }
        if (end - start < 1 || end - start > 50) {
            throw invalid();
        }
        value = new String(
                points,
                start,
                end - start
        );
    }

    private static boolean whitespace(int point) {
        return Character.isWhitespace(point) || Character.isSpaceChar(point);
    }

    private static boolean forbidden(int point) {
        return Character.isISOControl(point) || point == 0x2028 || point == 0x2029;
    }

    private static RecordingException invalid() {
        return new RecordingException(RecordingErrorCode.INVALID_SPEAKER_DISPLAY_NAME);
    }
}
