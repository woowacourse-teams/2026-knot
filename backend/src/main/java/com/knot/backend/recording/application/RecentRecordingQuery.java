package com.knot.backend.recording.application;

import com.knot.backend.recording.application.dto.result.RecentRecordingItemResult;
import com.knot.backend.recording.domain.model.RecentRecordingCursor;
import java.util.List;

public interface RecentRecordingQuery {

    List<RecentRecordingItemResult> findPage(
            long workspaceId,
            RecentRecordingCursor cursor,
            int limit
    );
}
