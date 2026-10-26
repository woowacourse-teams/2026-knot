package com.knot.backend.recording.application;

import com.knot.backend.recording.application.dto.result.RecentRecordingItemResult;
import com.knot.backend.recording.domain.model.RecentRecordingCursor;
import java.util.List;
import java.util.Optional;

public interface RecentRecordingQuery {

    List<RecentRecordingItemResult> findPage(
            long workspaceId,
            RecentRecordingCursor cursor,
            int limit
    );

    Optional<RecentRecordingItemResult> findItem(
            long workspaceId,
            long recordingId
    );

    boolean contains(
            long workspaceId,
            long recordingId
    );
}
