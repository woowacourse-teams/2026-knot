package com.knot.backend.recording.application;

import com.knot.backend.recording.application.dto.result.PresignedAudioChunk;
import com.knot.backend.recording.domain.model.StoredAudioChunk;
import java.time.Instant;

public interface RecordingAudioChunkStorage {

    PresignedAudioChunk presignUpload(
            String storageKey,
            long byteLength,
            String checksumSha256,
            Instant expiresAt
    );

    StoredAudioChunk findStoredObject(String storageKey);
}
