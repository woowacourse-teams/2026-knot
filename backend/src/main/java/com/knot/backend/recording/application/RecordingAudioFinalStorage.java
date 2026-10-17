package com.knot.backend.recording.application;

import com.knot.backend.recording.domain.model.AudioManifestPart;
import com.knot.backend.recording.domain.model.StoredAudioChunk;
import java.io.InputStream;
import java.nio.file.Path;

public interface RecordingAudioFinalStorage {
    InputStream openChunk(AudioManifestPart part);

    StoredAudioChunk uploadFinal(
            String storageKey,
            Path file,
            long byteLength,
            String checksumSha256
    );

    StoredAudioChunk findStoredFinal(String storageKey);
}
