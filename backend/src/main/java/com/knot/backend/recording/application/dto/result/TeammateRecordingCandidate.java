package com.knot.backend.recording.application.dto.result;

import com.knot.backend.recording.domain.model.RecordingSession;

public record TeammateRecordingCandidate(
        RecordingSession session,
        String starterNickname
) {
}
