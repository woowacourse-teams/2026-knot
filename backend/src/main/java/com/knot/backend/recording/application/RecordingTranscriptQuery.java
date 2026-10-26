package com.knot.backend.recording.application;

import com.knot.backend.recording.application.dto.result.RecordingTranscriptResult;
import java.util.Optional;

public interface RecordingTranscriptQuery {

    Optional<RecordingTranscriptResult> find(long recordingId);
}
