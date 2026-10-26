package com.knot.backend.recording.application.dto.result;

import com.knot.backend.recording.domain.model.TranscriptionDecodePolicy;
import java.util.UUID;

public record TranscriptionWindowClaim(
        long recordingId,
        long windowId,
        UUID attemptId,
        UUID ownerToken,
        long claimEpoch,
        String manifestJson,
        String manifestSha256,
        TranscriptionDecodePolicy decodePolicy
) {
}
