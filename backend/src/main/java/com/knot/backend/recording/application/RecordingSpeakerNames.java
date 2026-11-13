package com.knot.backend.recording.application;

import java.util.Map;

public interface RecordingSpeakerNames {
    Map<Integer, String> findByTranscriptId(long transcriptId);
    void save(
            long recordingId,
            int speakerNumber,
            String displayName
    );
}
