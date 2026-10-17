package com.knot.backend.recording.domain.model;

import com.knot.backend.recording.domain.RecordingErrorCode;
import com.knot.backend.recording.domain.RecordingException;
import java.util.Base64;

public record AudioChunkDescriptor(
        int sequence,
        long sampleOffset,
        int frameCount,
        long byteLength,
        String checksumSha256
) {
    public static final int MAX_FRAME_COUNT = 80000;
    public static final long MAX_TOTAL_FRAME_COUNT = 115200000L;

    public AudioChunkDescriptor {
        if (sequence < 1 || sampleOffset < 0 || frameCount < 1 || frameCount > MAX_FRAME_COUNT
                || byteLength != frameCount * 2L || sampleOffset > MAX_TOTAL_FRAME_COUNT - frameCount
                || !isCanonicalChecksum(checksumSha256)) {
            throw new RecordingException(RecordingErrorCode.INVALID_AUDIO_CHUNK);
        }
    }

    private static boolean isCanonicalChecksum(String checksum) {
        if (checksum == null) {
            return false;
        }
        try {
            byte[] decoded = Base64.getDecoder()
                    .decode(checksum);
            return decoded.length == 32 && Base64.getEncoder()
                    .encodeToString(decoded)
                    .equals(checksum);
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }
}
