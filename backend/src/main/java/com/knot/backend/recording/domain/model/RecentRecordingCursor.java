package com.knot.backend.recording.domain.model;

import com.knot.backend.recording.domain.RecordingErrorCode;
import com.knot.backend.recording.domain.RecordingException;
import java.nio.charset.StandardCharsets;
import java.time.DateTimeException;
import java.time.Instant;
import java.util.Base64;
import lombok.Getter;

@Getter
public class RecentRecordingCursor {
    private static final String VERSION = "1";
    private static final int FIELD_COUNT = 4;
    private static final int MAX_LENGTH = 512;

    private final long workspaceId;
    private final Instant startedAt;
    private final long recordingId;

    private RecentRecordingCursor(
            long workspaceId,
            Instant startedAt,
            long recordingId
    ) {
        if (workspaceId <= 0 || startedAt == null || recordingId <= 0) {
            throw invalid();
        }
        this.workspaceId = workspaceId;
        this.startedAt = startedAt;
        this.recordingId = recordingId;
    }

    public static RecentRecordingCursor of(
            long workspaceId,
            Instant startedAt,
            long recordingId
    ) {
        return new RecentRecordingCursor(
                workspaceId,
                startedAt,
                recordingId
        );
    }

    public static RecentRecordingCursor parse(
            String encoded,
            long workspaceId
    ) {
        if (encoded == null || encoded.isBlank() || encoded.length() > MAX_LENGTH) {
            throw invalid();
        }
        try {
            String[] fields = new String(
                    Base64.getUrlDecoder()
                            .decode(encoded),
                    StandardCharsets.UTF_8
            ).split(
                    "\\|",
                    -1
            );
            validatePayload(fields);
            RecentRecordingCursor cursor = of(
                    Long.parseLong(fields[1]),
                    Instant.parse(fields[2]),
                    Long.parseLong(fields[3])
            );
            cursor.validateWorkspace(workspaceId);
            return cursor;
        } catch (IllegalArgumentException | DateTimeException exception) {
            throw new RecordingException(
                    RecordingErrorCode.INVALID_PARAMETER,
                    exception
            );
        }
    }

    public String encode() {
        String payload = VERSION + "|" + workspaceId + "|" + startedAt + "|" + recordingId;
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(payload.getBytes(StandardCharsets.UTF_8));
    }

    private static void validatePayload(String[] fields) {
        if (fields.length != FIELD_COUNT || !VERSION.equals(fields[0])) {
            throw invalid();
        }
    }

    private void validateWorkspace(long requestedWorkspaceId) {
        if (workspaceId != requestedWorkspaceId) {
            throw invalid();
        }
    }

    private static RecordingException invalid() {
        return new RecordingException(RecordingErrorCode.INVALID_PARAMETER);
    }
}
