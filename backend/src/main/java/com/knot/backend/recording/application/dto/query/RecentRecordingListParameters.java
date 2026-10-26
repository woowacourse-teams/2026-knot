package com.knot.backend.recording.application.dto.query;

import com.knot.backend.recording.domain.RecordingErrorCode;
import com.knot.backend.recording.domain.RecordingException;

public record RecentRecordingListParameters(
        String cursor,
        int size
) {
    private static final int DEFAULT_SIZE = 20;
    private static final int MAX_SIZE = 100;

    public RecentRecordingListParameters {
        if (size < 1 || size > MAX_SIZE || (cursor != null && cursor.isBlank())) {
            throw new RecordingException(RecordingErrorCode.INVALID_PARAMETER);
        }
    }

    public static RecentRecordingListParameters of(
            String cursor,
            Integer size
    ) {
        return new RecentRecordingListParameters(
                cursor,
                resolveSize(size)
        );
    }

    private static int resolveSize(Integer size) {
        if (size == null) {
            return DEFAULT_SIZE;
        }
        return size;
    }
}
