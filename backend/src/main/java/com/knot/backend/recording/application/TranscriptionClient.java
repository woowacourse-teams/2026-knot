package com.knot.backend.recording.application;

import com.knot.backend.recording.application.dto.result.AiTranscriptionJob;
import java.util.UUID;

public interface TranscriptionClient {
    default AiTranscriptionJob submit(
            UUID attemptId,
            String downloadUrl
    ) {
        return submit(
                attemptId,
                downloadUrl,
                null
        );
    }

    AiTranscriptionJob submit(
            UUID attemptId,
            String downloadUrl,
            Integer speakerCount
    );
    AiTranscriptionJob get(UUID attemptId);
}
