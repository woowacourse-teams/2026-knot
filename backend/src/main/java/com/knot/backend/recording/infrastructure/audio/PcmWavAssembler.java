package com.knot.backend.recording.infrastructure.audio;

import com.knot.backend.recording.application.RecordingAudioAssembler;
import com.knot.backend.recording.application.RecordingAudioFinalStorage;
import com.knot.backend.recording.application.dto.result.FinalizedAudioFile;
import com.knot.backend.recording.domain.RecordingErrorCode;
import com.knot.backend.recording.domain.RecordingException;
import com.knot.backend.recording.domain.model.AudioManifestPart;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.List;
import java.util.function.LongSupplier;

public class PcmWavAssembler implements RecordingAudioAssembler {
    private static final long MINIMUM_USABLE_BYTES = 512L * 1024 * 1024;
    private static final long MAXIMUM_FRAMES = 115_200_000L;
    private static final int BUFFER_SIZE = 16 * 1024;

    private final RecordingAudioFinalStorage storage;
    private final Path directory;
    private final LongSupplier usableSpace;

    public PcmWavAssembler(RecordingAudioFinalStorage storage) {
        this(
                storage,
                Path.of(System.getProperty("java.io.tmpdir")),
                () -> Path.of(System.getProperty("java.io.tmpdir"))
                        .toFile()
                        .getUsableSpace()
        );
    }

    public PcmWavAssembler(
            RecordingAudioFinalStorage storage,
            Path directory,
            LongSupplier usableSpace
    ) {
        this.storage = storage;
        this.directory = directory;
        this.usableSpace = usableSpace;
    }

    @Override
    public FinalizedAudioFile assemble(
            List<AudioManifestPart> parts,
            long totalFrames,
            Runnable checkpoint
    ) {
        validateManifest(
                parts,
                totalFrames
        );
        checkpoint.run();
        if (usableSpace.getAsLong() < MINIMUM_USABLE_BYTES) {
            throw new RecordingException(RecordingErrorCode.AUDIO_STORAGE_UNAVAILABLE);
        }
        Path file = null;
        try {
            // 실행자가 만든 파일만 삭제한다. 다른 실행자의 임시 파일을 검색하거나 정리하지 않는다.
            file = Files.createFile(
                    directory.resolve(temporaryFilePrefix() + ".tmp"),
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))
            );
            MessageDigest digest = digest();
            try (OutputStream output = new DigestOutputStream(
                    Files.newOutputStream(file),
                    digest
            )) {
                output.write(header(totalFrames * 2));
                byte[] buffer = new byte[BUFFER_SIZE];
                for (AudioManifestPart part : parts) {
                    checkpoint.run();
                    if (part.isMissing()) {
                        writeSilence(
                                output,
                                part.byteLength(),
                                checkpoint
                        );
                        continue;
                    }
                    copyPart(
                            part,
                            output,
                            buffer,
                            checkpoint
                    );
                }
                checkpoint.run();
            }
            return new FinalizedAudioFile(
                    file,
                    totalFrames * 2 + 44,
                    Base64.getEncoder()
                            .encodeToString(digest.digest())
            );
        } catch (IOException exception) {
            deleteFailedFile(
                    file,
                    exception
            );
            throw new RecordingException(
                    RecordingErrorCode.AUDIO_STORAGE_UNAVAILABLE,
                    exception
            );
        } catch (RuntimeException exception) {
            deleteFailedFile(
                    file,
                    exception
            );
            throw exception;
        }
    }

    private void copyPart(
            AudioManifestPart part,
            OutputStream output,
            byte[] buffer,
            Runnable checkpoint
    ) throws IOException {
        MessageDigest digest = digest();
        long remaining = part.byteLength();
        try (InputStream input = storage.openChunk(part)) {
            while (remaining > 0) {
                checkpoint.run();
                int count = input.read(
                        buffer,
                        0,
                        (int) Math.min(
                                buffer.length,
                                remaining
                        )
                );
                if (count < 0) {
                    throw new RecordingException(RecordingErrorCode.AUDIO_CHUNK_MISMATCH);
                }
                if (count == 0) {
                    continue;
                }
                digest.update(
                        buffer,
                        0,
                        count
                );
                checkpoint.run();
                output.write(
                        buffer,
                        0,
                        count
                );
                remaining -= count;
            }
            checkpoint.run();
            if (input.read() != -1 || !Base64.getEncoder()
                    .encodeToString(digest.digest())
                    .equals(part.checksumSha256())) {
                throw new RecordingException(RecordingErrorCode.AUDIO_CHUNK_MISMATCH);
            }
        }
    }

    private void writeSilence(
            OutputStream output,
            long length,
            Runnable checkpoint
    ) throws IOException {
        byte[] zeroes = new byte[BUFFER_SIZE];
        long remaining = length;
        while (remaining > 0) {
            checkpoint.run();
            int count = (int) Math.min(
                    remaining,
                    zeroes.length
            );
            output.write(
                    zeroes,
                    0,
                    count
            );
            remaining -= count;
        }
    }

    private void validateManifest(
            List<AudioManifestPart> parts,
            long totalFrames
    ) {
        if (parts == null || totalFrames <= 0 || totalFrames > MAXIMUM_FRAMES) {
            throw new RecordingException(RecordingErrorCode.INVALID_AUDIO_CHUNK);
        }
        long offset = 0;
        int sequence = 1;
        for (AudioManifestPart part : parts) {
            if (part == null || part.sequence() != sequence || part.sampleOffset() != offset || part.frameCount() <= 0
                    || part.frameCount() > 80_000
                    || (!part.isMissing()
                            && (part.storageKey() == null || part.checksumSha256() == null || part.versionId()
                                    .isBlank() || "null".equals(part.versionId())))) {
                throw new RecordingException(RecordingErrorCode.INVALID_AUDIO_CHUNK);
            }
            offset += part.frameCount();
            sequence++;
        }
        if (offset != totalFrames) {
            throw new RecordingException(RecordingErrorCode.INVALID_AUDIO_CHUNK);
        }
    }

    private String temporaryFilePrefix() {
        long pid = ProcessHandle.current()
                .pid();
        long startedAt = ProcessHandle.current()
                .info()
                .startInstant()
                .orElseThrow(() -> new RecordingException(RecordingErrorCode.AUDIO_STORAGE_UNAVAILABLE))
                .toEpochMilli();
        return "knot-recording-wav-" + pid + "-" + startedAt + "-" + java.util.UUID.randomUUID();
    }

    private byte[] header(long dataLength) {
        ByteBuffer header = ByteBuffer.allocate(44)
                .order(ByteOrder.LITTLE_ENDIAN);
        header.put("RIFF".getBytes(StandardCharsets.US_ASCII));
        header.putInt((int) dataLength + 36);
        header.put("WAVEfmt ".getBytes(StandardCharsets.US_ASCII));
        header.putInt(16);
        header.putShort((short) 1);
        header.putShort((short) 1);
        header.putInt(16_000);
        header.putInt(32_000);
        header.putShort((short) 2);
        header.putShort((short) 16);
        header.put("data".getBytes(StandardCharsets.US_ASCII));
        header.putInt((int) dataLength);
        return header.array();
    }

    private MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new RecordingException(
                    RecordingErrorCode.AUDIO_STORAGE_UNAVAILABLE,
                    exception
            );
        }
    }

    private void deleteFailedFile(
            Path file,
            Exception failure
    ) {
        if (file == null) {
            return;
        }
        try {
            Files.deleteIfExists(file);
        } catch (IOException exception) {
            failure.addSuppressed(exception);
        }
    }
}
