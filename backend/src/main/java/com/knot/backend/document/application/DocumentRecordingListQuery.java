package com.knot.backend.document.application;

import com.knot.backend.document.application.dto.result.DocumentRecordingItemResult;
import com.knot.backend.recording.domain.model.RecentRecordingCursor;
import java.util.List;

public interface DocumentRecordingListQuery {

    List<DocumentRecordingItemResult> findPage(
            long workspaceId,
            RecentRecordingCursor cursor,
            int limit
    );
}
