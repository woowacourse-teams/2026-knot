package com.knot.backend.recording.application;

import com.knot.backend.recording.application.dto.result.RecordingOutputStatus;
import java.util.List;
import java.util.Map;

public interface RecordingOutputQuery {
    Map<Long, RecordingOutputStatus> findAll(
            long workspaceId,
            List<Long> recordingIds
    );
}
