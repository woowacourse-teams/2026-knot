package com.knot.backend.workspace.domain.model;

import com.knot.backend.workspace.domain.WorkspaceErrorCode;
import com.knot.backend.workspace.domain.WorkspaceException;
import java.nio.charset.StandardCharsets;
import java.time.DateTimeException;
import java.time.Instant;
import java.util.Base64;
import lombok.Getter;

@Getter
public class WorkspaceMemberCursor {
    private static final String VERSION = "1";
    private static final int FIELD_COUNT = 4;
    private static final int MAX_LENGTH = 512;

    private final long workspaceId;
    private final Instant joinedAt;
    private final long memberId;

    private WorkspaceMemberCursor(
            long workspaceId,
            Instant joinedAt,
            long memberId
    ) {
        if (workspaceId <= 0 || joinedAt == null || memberId <= 0) {
            throw invalid();
        }
        this.workspaceId = workspaceId;
        this.joinedAt = joinedAt;
        this.memberId = memberId;
    }

    public static WorkspaceMemberCursor of(
            long workspaceId,
            Instant joinedAt,
            long memberId
    ) {
        return new WorkspaceMemberCursor(
                workspaceId,
                joinedAt,
                memberId
        );
    }

    public static WorkspaceMemberCursor parse(
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
            WorkspaceMemberCursor cursor = of(
                    Long.parseLong(fields[1]),
                    Instant.parse(fields[2]),
                    Long.parseLong(fields[3])
            );
            cursor.validateWorkspace(workspaceId);
            return cursor;
        } catch (IllegalArgumentException | DateTimeException exception) {
            throw new WorkspaceException(
                    WorkspaceErrorCode.INVALID_PARAMETER,
                    exception
            );
        }
    }

    public String encode() {
        String payload = VERSION + "|" + workspaceId + "|" + joinedAt + "|" + memberId;
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

    private static WorkspaceException invalid() {
        return new WorkspaceException(WorkspaceErrorCode.INVALID_PARAMETER);
    }
}
