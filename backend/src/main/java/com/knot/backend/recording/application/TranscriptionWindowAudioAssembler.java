package com.knot.backend.recording.application;

import com.knot.backend.recording.application.dto.result.TranscriptionWindowAudioSource;
import com.knot.backend.recording.application.dto.result.PreparedWindowSource;
import com.knot.backend.recording.domain.RecordingErrorCode;
import com.knot.backend.recording.domain.RecordingException;
import com.knot.backend.recording.domain.model.AudioManifestPart;
import com.knot.backend.recording.domain.model.TranscriptionWindowShape;
import com.knot.backend.recording.domain.model.StoredAudioChunk;
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
import java.util.HexFormat;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
public class TranscriptionWindowAudioAssembler {
    private static final long MAX_RECORDING_FRAMES = 115_200_000;
    private static final int BUFFER_SIZE = 16_384;

    private final RecordingAudioFinalStorage storage;
    private final ObjectMapper mapper;
    private final Path directory;

    @Autowired
    public TranscriptionWindowAudioAssembler(
            RecordingAudioFinalStorage storage,
            ObjectMapper mapper
    ) {
        this(
                storage,
                mapper,
                Path.of(System.getProperty("java.io.tmpdir"))
        );
    }

    TranscriptionWindowAudioAssembler(
            RecordingAudioFinalStorage storage,
            ObjectMapper mapper,
            Path directory
    ) {
        this.storage = storage;
        this.mapper = mapper;
        this.directory = directory;
    }

    public MaterializedWindowAudio materialize(TranscriptionWindowAudioSource source) {
        return materialize(
                source,
                () -> {
                }
        );
    }

    public MaterializedWindowAudio materialize(
            TranscriptionWindowAudioSource source,
            Runnable checkpoint
    ) {
        WindowManifest manifest = validate(source);
        checkpoint.run();
        Path file = null;
        try {
            file = Files.createFile(
                    directory.resolve(temporaryFileName()),
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))
            );
            long dataLength = (manifest.inputEndFrame() - manifest.inputStartFrame()) * 2;
            MessageDigest hash = digest();
            try (OutputStream output = new DigestOutputStream(
                    Files.newOutputStream(file),
                    hash
            )) {
                output.write(header(dataLength));
                if (source.finalStorageKey() != null) {
                    try (InputStream input = storage.openFinalRange(
                            source.finalStorageKey(),
                            source.finalVersionId(),
                            44 + manifest.inputStartFrame() * 2,
                            dataLength
                    )) {
                        copyExact(
                                input,
                                output,
                                dataLength,
                                checkpoint
                        );
                    }
                } else {
                    for (AudioManifestPart part : manifest.parts()) {
                        copyPart(
                                part,
                                manifest,
                                output,
                                checkpoint
                        );
                    }
                }
            }
            checkpoint.run();
            String key = "recordings/" + source.recordingId() + "/windows/" + source.manifestSha256() + ".wav";
            String checksum = Base64.getEncoder()
                    .encodeToString(hash.digest());
            StoredAudioChunk stored = storage.uploadFinal(
                    key,
                    file,
                    dataLength + 44,
                    checksum
            );
            validateStored(
                    stored,
                    dataLength + 44,
                    checksum
            );
            checkpoint.run();
            return new MaterializedWindowAudio(
                    key,
                    stored
            );
        } catch (IOException exception) {
            throw new RecordingException(
                    RecordingErrorCode.AUDIO_STORAGE_UNAVAILABLE,
                    exception
            );
        } finally {
            if (file != null) {
                try {
                    Files.deleteIfExists(file);
                } catch (IOException exception) {
                    throw new RecordingException(
                            RecordingErrorCode.AUDIO_STORAGE_UNAVAILABLE,
                            exception
                    );
                }
            }
        }
    }

    private String temporaryFileName() {
        ProcessHandle process = ProcessHandle.current();
        long startedAt = process.info()
                .startInstant()
                .orElseThrow(() -> new RecordingException(RecordingErrorCode.AUDIO_STORAGE_UNAVAILABLE))
                .toEpochMilli();
        // 기존 시작 정리기가 같은 소유자·권한·프로세스 시작 시각을 검증한다.
        return "knot-recording-wav-" + process.pid() + "-" + startedAt + "-" + java.util.UUID.randomUUID() + ".tmp";
    }

    private WindowManifest validate(TranscriptionWindowAudioSource source) {
        if (source == null || source.recordingId() <= 0 || source.windowId() <= 0 || source.pipelineVersion() == null
                || source.pipelineVersion()
                        .isBlank()
                || source.manifestJson() == null || source.manifestJson()
                        .getBytes(StandardCharsets.UTF_8).length > 1_048_576
                || source.manifestSha256() == null || !source.manifestSha256()
                        .matches("[a-f0-9]{64}")
                || !HexFormat.of()
                        .formatHex(
                                digest().digest(
                                        source.manifestJson()
                                                .getBytes(StandardCharsets.UTF_8)
                                )
                        )
                        .equals(source.manifestSha256())) {
            throw mismatch();
        }
        WindowManifest manifest;
        try {
            manifest = mapper.readValue(
                    source.manifestJson(),
                    WindowManifest.class
            );
        } catch (RuntimeException exception) {
            throw new RecordingException(
                    RecordingErrorCode.AUDIO_CHUNK_MISMATCH,
                    exception
            );
        }
        if (manifest == null || (manifest.schemaVersion() != 1 && manifest.schemaVersion() != 2)
                || manifest.inputStartFrame() < 0 || manifest.inputEndFrame() <= manifest.inputStartFrame()
                || manifest.inputEndFrame() > MAX_RECORDING_FRAMES
                || manifest.inputEndFrame() - manifest.inputStartFrame() > TranscriptionWindowShape.MAX_INPUT_FRAMES) {
            throw mismatch();
        }
        if (manifest.schemaVersion() == 2) {
            validatePrepared(
                    source,
                    manifest
            );
            return manifest;
        }
        if (manifest.parts() == null || manifest.parts()
                .isEmpty() || manifest.source() != null || manifest.sourceKind() != null) {
            throw mismatch();
        }
        boolean finalPresent = source.finalStorageKey() != null;
        if (finalPresent) {
            if (!source.finalStorageKey()
                    .matches("recordings/" + source.recordingId() + "/final/[1-9][0-9]*/[a-f0-9]{64}\\.wav")
                    || !version(source.finalVersionId()) || !checksum(source.finalChecksumSha256())
                    || source.finalByteLength() < 44 + manifest.inputEndFrame() * 2
                    || source.finalByteLength() > 44 + MAX_RECORDING_FRAMES * 2
                    || (source.finalByteLength() - 44) % 2 != 0) {
                throw mismatch();
            }
        } else if (source.finalVersionId() != null || source.finalChecksumSha256() != null
                || source.finalByteLength() != 0) {
            throw mismatch();
        }
        long previousEnd = -1;
        int previousSequence = -1;
        for (AudioManifestPart part : manifest.parts()) {
            if (part == null || part.sequence() <= 0 || part.sampleOffset() < 0 || part.frameCount() <= 0
                    || part.frameCount() > 80_000 || part.sampleOffset() > MAX_RECORDING_FRAMES - part.frameCount()
                    || part.sampleOffset() >= manifest.inputEndFrame()
                    || part.sampleOffset() + part.frameCount() <= manifest.inputStartFrame()
                    || (previousEnd >= 0
                            && (previousEnd != part.sampleOffset() || previousSequence + 1 != part.sequence()))
                    || (previousEnd < 0 && part.sampleOffset() > manifest.inputStartFrame())
                    || part.storageKey() == null || !part.storageKey()
                            .equals("recordings/" + source.recordingId() + "/chunks/" + part.sequence())
                    || !checksum(part.checksumSha256()) || (!part.isMissing() && !version(part.versionId()))
                    || (part.isMissing() && !finalPresent)) {
                throw mismatch();
            }
            previousEnd = part.sampleOffset() + part.frameCount();
            previousSequence = part.sequence();
        }
        if (previousEnd < manifest.inputEndFrame()) {
            throw mismatch();
        }
        return manifest;
    }

    private void validatePrepared(
            TranscriptionWindowAudioSource source,
            WindowManifest manifest
    ) {
        PreparedWindowSource prepared = manifest.source();
        if (!"PREPARED_WAV".equals(manifest.sourceKind()) || manifest.parts() != null || prepared == null
                || prepared.storageKey() == null || !prepared.storageKey()
                        .matches("recordings/" + source.recordingId() + "/prepared/[a-f0-9-]{36}/[a-f0-9]{64}\\.wav")
                || !prepared.storageKey()
                        .equals(source.finalStorageKey())
                || !version(prepared.versionId()) || !prepared.versionId()
                        .equals(source.finalVersionId())
                || !checksum(prepared.checksumSha256()) || !prepared.checksumSha256()
                        .equals(source.finalChecksumSha256())
                || prepared.totalFrames() < manifest.inputEndFrame() || prepared.totalFrames() > MAX_RECORDING_FRAMES
                || prepared.byteLength() != 44 + prepared.totalFrames() * 2
                || prepared.byteLength() != source.finalByteLength()) {
            throw mismatch();
        }
    }

    private void copyPart(
            AudioManifestPart part,
            WindowManifest manifest,
            OutputStream output,
            Runnable checkpoint
    ) throws IOException {
        MessageDigest hash = digest();
        long offset = 0;
        long from = Math.max(
                manifest.inputStartFrame() - part.sampleOffset(),
                0
        ) * 2;
        long to = Math.min(
                manifest.inputEndFrame() - part.sampleOffset(),
                part.frameCount()
        ) * 2;
        byte[] buffer = new byte[BUFFER_SIZE];
        checkpoint.run();
        try (InputStream input = storage.openChunk(part)) {
            while (offset < part.byteLength()) {
                checkpoint.run();
                int count = input.read(
                        buffer,
                        0,
                        (int) Math.min(
                                buffer.length,
                                part.byteLength() - offset
                        )
                );
                if (count < 0) {
                    throw mismatch();
                }
                if (count == 0) {
                    continue;
                }
                hash.update(
                        buffer,
                        0,
                        count
                );
                long start = Math.max(
                        offset,
                        from
                );
                long end = Math.min(
                        offset + count,
                        to
                );
                if (end > start) {
                    output.write(
                            buffer,
                            (int) (start - offset),
                            (int) (end - start)
                    );
                }
                offset += count;
            }
            if (input.read() != -1 || !Base64.getEncoder()
                    .encodeToString(hash.digest())
                    .equals(part.checksumSha256())) {
                throw mismatch();
            }
        }
    }

    private void copyExact(
            InputStream input,
            OutputStream output,
            long length,
            Runnable checkpoint
    ) throws IOException {
        byte[] buffer = new byte[BUFFER_SIZE];
        long remaining = length;
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
                throw mismatch();
            }
            if (count > 0) {
                output.write(
                        buffer,
                        0,
                        count
                );
                remaining -= count;
            }
        }
        if (input.read() != -1) {
            throw mismatch();
        }
    }

    private void validateStored(
            StoredAudioChunk stored,
            long byteLength,
            String checksum
    ) {
        if (stored == null || !stored.exists() || stored.byteLength() != byteLength
                || !"audio/wav".equals(stored.contentType()) || !checksum.equals(stored.checksumSha256())
                || !version(stored.versionId())) {
            throw mismatch();
        }
    }

    private boolean checksum(String value) {
        return value != null && value.matches("[A-Za-z0-9+/]{43}=");
    }

    private boolean version(String value) {
        return value != null && !value.isBlank() && !"null".equals(value);
    }

    private byte[] header(long length) {
        return ByteBuffer.allocate(44)
                .order(ByteOrder.LITTLE_ENDIAN)
                .put("RIFF".getBytes(StandardCharsets.US_ASCII))
                .putInt((int) length + 36)
                .put("WAVEfmt ".getBytes(StandardCharsets.US_ASCII))
                .putInt(16)
                .putShort((short) 1)
                .putShort((short) 1)
                .putInt(16_000)
                .putInt(32_000)
                .putShort((short) 2)
                .putShort((short) 16)
                .put("data".getBytes(StandardCharsets.US_ASCII))
                .putInt((int) length)
                .array();
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

    private RecordingException mismatch() {
        return new RecordingException(RecordingErrorCode.AUDIO_CHUNK_MISMATCH);
    }

    public record MaterializedWindowAudio(
            String storageKey,
            StoredAudioChunk metadata
    ) {
    }

    private record WindowManifest(
            int schemaVersion,
            long inputStartFrame,
            long inputEndFrame,
            List<AudioManifestPart> parts,
            String sourceKind,
            PreparedWindowSource source
    ) {
    }
}
