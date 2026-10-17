package com.knot.backend.recording.domain.model;

public record StoredAudioChunk(
        boolean exists,
        long byteLength,
        String contentType,
        String checksumSha256,
        String versionId
) {
    public static StoredAudioChunk missing() {
        return new StoredAudioChunk(
                false,
                0,
                null,
                null,
                null
        );
    }
}
