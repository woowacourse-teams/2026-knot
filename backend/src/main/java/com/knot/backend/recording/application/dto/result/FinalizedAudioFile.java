package com.knot.backend.recording.application.dto.result;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public record FinalizedAudioFile(
        Path path,
        long byteLength,
        String checksumSha256
) implements AutoCloseable {
    @Override
    public void close() throws IOException {
        Files.deleteIfExists(path);
    }
}
