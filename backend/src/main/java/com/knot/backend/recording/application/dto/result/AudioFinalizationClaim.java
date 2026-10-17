package com.knot.backend.recording.application.dto.result;
import java.util.UUID;
public record AudioFinalizationClaim(
        long recordingId,
        UUID ownerToken,
        long claimEpoch
) {
}
