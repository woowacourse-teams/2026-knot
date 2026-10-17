package com.knot.backend.recording.domain.model;

public record AudioManifestPart(
        int sequence,
        long sampleOffset,
        int frameCount,
        String storageKey,
        String checksumSha256,
        String versionId
) {
    @com.fasterxml.jackson.annotation.JsonIgnore
    public boolean isMissing() {
        return versionId == null;
    }

    public long byteLength() {
        return (long) frameCount * 2;
    }
}
