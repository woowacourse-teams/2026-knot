package com.knot.backend.recording.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.knot.backend.recording.application.dto.result.TranscriptionWindowAudioSource;
import com.knot.backend.recording.domain.RecordingException;
import com.knot.backend.recording.domain.model.AudioManifestPart;
import com.knot.backend.recording.domain.model.StoredAudioChunk;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

class TranscriptionWindowAudioAssemblerTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final RecordingAudioFinalStorage storage = mock(RecordingAudioFinalStorage.class);
    @TempDir
    Path directory;

    @Test
    void materialize_clipsVerifiedWholePartsAndDeletesPrivateFile() throws Exception {
        // given
        byte[] pcm = {1, 2, 3, 4, 5, 6, 7, 8};
        TranscriptionWindowAudioSource source = source(
                part(pcm),
                false
        );
        when(storage.openChunk(any())).thenReturn(new ByteArrayInputStream(pcm));
        AtomicReference<byte[]> uploaded = captureUpload();

        // when
        TranscriptionWindowAudioAssembler.MaterializedWindowAudio result = assembler().materialize(source);

        // then
        assertThat(uploaded.get()).hasSize(48);
        assertThat(
                java.util.Arrays.copyOfRange(
                        uploaded.get(),
                        44,
                        48
                )
        ).containsExactly(
                3,
                4,
                5,
                6
        );
        assertThat(result.storageKey()).isEqualTo("recordings/7/windows/" + source.manifestSha256() + ".wav");
        assertEmptyDirectory();
    }

    @Test
    void materialize_rejectsCorruptionOutsideClippedRange() throws Exception {
        // given
        byte[] original = {1, 2, 3, 4, 5, 6, 7, 8};
        when(storage.openChunk(any())).thenReturn(new ByteArrayInputStream(new byte[]{9, 2, 3, 4, 5, 6, 7, 8}));
        TranscriptionWindowAudioSource source = source(
                part(original),
                false
        );

        // when
        Throwable failure = catchThrowable(() -> assembler().materialize(source));

        // then
        assertThat(failure).isInstanceOf(RecordingException.class);
        verify(
                storage,
                never()
        ).uploadFinal(
                anyString(),
                any(),
                anyLong(),
                anyString()
        );
        assertEmptyDirectory();
    }

    @Test
    void materialize_usesExactFinalRangeWhenRawPartsAreMissing() throws Exception {
        // given
        TranscriptionWindowAudioSource source = source(
                new AudioManifestPart(
                        1,
                        0,
                        4,
                        "recordings/7/chunks/1",
                        checksum(new byte[8]),
                        null
                ),
                true
        );
        when(
                storage.openFinalRange(
                        source.finalStorageKey(),
                        "final-version",
                        46,
                        4
                )
        ).thenReturn(new ByteArrayInputStream(new byte[]{3, 4, 5, 6}));
        AtomicReference<byte[]> uploaded = captureUpload();

        // when
        assembler().materialize(source);

        // then
        assertThat(
                java.util.Arrays.copyOfRange(
                        uploaded.get(),
                        44,
                        48
                )
        ).containsExactly(
                3,
                4,
                5,
                6
        );
        verify(
                storage,
                never()
        ).openChunk(any());
        assertEmptyDirectory();
    }

    @Test
    void materialize_rejectsMissingPartsWithoutFrozenFinalSource() throws Exception {
        // given
        TranscriptionWindowAudioSource source = source(
                new AudioManifestPart(
                        1,
                        0,
                        4,
                        "recordings/7/chunks/1",
                        checksum(new byte[8]),
                        null
                ),
                false
        );

        // when
        Throwable failure = catchThrowable(() -> assembler().materialize(source));

        // then
        assertThat(failure).isInstanceOf(RecordingException.class);
        assertEmptyDirectory();
    }

    @Test
    void materialize_rejectsTruncatedFinalRangeAndDeletesTemporaryFile() throws Exception {
        // given
        TranscriptionWindowAudioSource source = source(
                part(new byte[8]),
                true
        );
        when(
                storage.openFinalRange(
                        source.finalStorageKey(),
                        "final-version",
                        46,
                        4
                )
        ).thenReturn(new ByteArrayInputStream(new byte[2]));

        // when
        Throwable failure = catchThrowable(() -> assembler().materialize(source));

        // then
        assertThat(failure).isInstanceOf(RecordingException.class);
        verify(
                storage,
                never()
        ).uploadFinal(
                anyString(),
                any(),
                anyLong(),
                anyString()
        );
        assertEmptyDirectory();
    }

    @Test
    void materialize_cancellationAfterFileCreationDeletesTemporaryFile() throws Exception {
        // given
        TranscriptionWindowAudioSource source = source(
                part(new byte[8]),
                false
        );
        java.util.concurrent.atomic.AtomicInteger checks = new java.util.concurrent.atomic.AtomicInteger();
        Runnable checkpoint = () -> {
            if (checks.incrementAndGet() == 2) {
                throw new IllegalStateException("취소됨");
            }
        };

        // when
        Throwable failure = catchThrowable(
                () -> assembler().materialize(
                        source,
                        checkpoint
                )
        );

        // then
        assertThat(failure).isInstanceOf(IllegalStateException.class);
        assertEmptyDirectory();
    }

    @Test
    void materialize_rejectsModifiedManifest() throws Exception {
        // given
        TranscriptionWindowAudioSource source = source(
                part(new byte[8]),
                false
        );
        TranscriptionWindowAudioSource altered = new TranscriptionWindowAudioSource(
                1,
                7,
                "pipeline",
                source.manifestJson() + " ",
                source.manifestSha256(),
                null,
                null,
                null,
                0
        );

        // when
        Throwable failure = catchThrowable(() -> assembler().materialize(altered));

        // then
        assertThat(failure).isInstanceOf(RecordingException.class);
        assertEmptyDirectory();
    }

    @Test
    void materialize_repeatedAttemptUsesIdenticalArtifactIdentity() throws Exception {
        // given
        byte[] pcm = new byte[8];
        TranscriptionWindowAudioSource source = source(
                part(pcm),
                false
        );
        when(storage.openChunk(any())).thenAnswer(invocation -> new ByteArrayInputStream(pcm));
        captureUpload();
        TranscriptionWindowAudioAssembler.MaterializedWindowAudio first = assembler().materialize(source);

        // when
        TranscriptionWindowAudioAssembler.MaterializedWindowAudio second = assembler().materialize(source);

        // then
        assertThat(second).isEqualTo(first);
        assertEmptyDirectory();
    }

    @Test
    void materialize_rejectsHoleBetweenParts() throws Exception {
        // given
        AudioManifestPart first = new AudioManifestPart(
                1,
                0,
                2,
                "recordings/7/chunks/1",
                checksum(new byte[4]),
                "v1"
        );
        AudioManifestPart second = new AudioManifestPart(
                2,
                3,
                2,
                "recordings/7/chunks/2",
                checksum(new byte[4]),
                "v2"
        );
        String json = mapper.writeValueAsString(
                Map.of(
                        "schemaVersion",
                        1,
                        "inputStartFrame",
                        1,
                        "inputEndFrame",
                        4,
                        "parts",
                        List.of(
                                first,
                                second
                        )
                )
        );
        String hash = HexFormat.of()
                .formatHex(
                        MessageDigest.getInstance("SHA-256")
                                .digest(json.getBytes(StandardCharsets.UTF_8))
                );
        TranscriptionWindowAudioSource source = new TranscriptionWindowAudioSource(
                1,
                7,
                "pipeline",
                json,
                hash,
                null,
                null,
                null,
                0
        );

        // when
        Throwable failure = catchThrowable(() -> assembler().materialize(source));

        // then
        assertThat(failure).isInstanceOf(RecordingException.class);
        verify(
                storage,
                never()
        ).openChunk(any());
        assertEmptyDirectory();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void materialize_acceptsUniqueChunkKeyForRawAndFinalAudio(boolean finalized) throws Exception {
        // given
        byte[] pcm = {1, 2, 3, 4, 5, 6, 7, 8};
        AudioManifestPart part = new AudioManifestPart(
                1,
                0,
                4,
                "recordings/7/chunks/1/12345678-1234-4234-8234-123456789abc",
                checksum(pcm),
                "chunk-version"
        );
        TranscriptionWindowAudioSource source = source(
                part,
                finalized
        );
        when(storage.openChunk(part)).thenReturn(new ByteArrayInputStream(pcm));
        when(
                storage.openFinalRange(
                        anyString(),
                        anyString(),
                        anyLong(),
                        anyLong()
                )
        ).thenReturn(new ByteArrayInputStream(new byte[]{3, 4, 5, 6}));
        AtomicReference<byte[]> uploaded = captureUpload();

        // when
        assembler().materialize(source);

        // then
        assertThat(
                java.util.Arrays.copyOfRange(
                        uploaded.get(),
                        44,
                        48
                )
        ).containsExactly(
                3,
                4,
                5,
                6
        );
        assertEmptyDirectory();
    }

    @ParameterizedTest
    @ValueSource(strings = {"recordings/8/chunks/1/12345678-1234-4234-8234-123456789abc",
            "recordings/7/chunks/2/12345678-1234-4234-8234-123456789abc", "recordings/7/chunks/1/../2",
            "recordings/7/chunks/1/not-a-uuid", "recordings/7/chunks/1/12345678-1234-4234-8234-123456789abc/extra"})
    void materialize_rejectsChunkKeyOutsideRecordingSequenceOrFormat(String key) throws Exception {
        // given
        AudioManifestPart part = new AudioManifestPart(
                1,
                0,
                4,
                key,
                checksum(new byte[8]),
                "chunk-version"
        );
        TranscriptionWindowAudioSource source = source(
                part,
                false
        );

        // when
        Throwable failure = catchThrowable(() -> assembler().materialize(source));

        // then
        assertThat(failure).isInstanceOf(RecordingException.class);
        verify(
                storage,
                never()
        ).openChunk(any());
        assertEmptyDirectory();
    }

    private TranscriptionWindowAudioAssembler assembler() {
        return new TranscriptionWindowAudioAssembler(
                storage,
                mapper,
                directory
        );
    }

    private AudioManifestPart part(byte[] bytes) throws Exception {
        return new AudioManifestPart(
                1,
                0,
                bytes.length / 2,
                "recordings/7/chunks/1",
                checksum(bytes),
                "chunk-version"
        );
    }

    private TranscriptionWindowAudioSource source(
            AudioManifestPart part,
            boolean finalized
    ) throws Exception {
        String json = mapper.writeValueAsString(
                Map.of(
                        "schemaVersion",
                        1,
                        "inputStartFrame",
                        1,
                        "inputEndFrame",
                        3,
                        "parts",
                        List.of(part)
                )
        );
        String hash = HexFormat.of()
                .formatHex(
                        MessageDigest.getInstance("SHA-256")
                                .digest(json.getBytes(StandardCharsets.UTF_8))
                );
        return new TranscriptionWindowAudioSource(
                1,
                7,
                "pipeline",
                json,
                hash,
                finalized ? "recordings/7/final/1/" + "a".repeat(64) + ".wav" : null,
                finalized ? "final-version" : null,
                finalized ? checksum(new byte[8]) : null,
                finalized ? 52 : 0
        );
    }

    private AtomicReference<byte[]> captureUpload() throws Exception {
        AtomicReference<byte[]> bytes = new AtomicReference<>();
        when(
                storage.uploadFinal(
                        anyString(),
                        any(),
                        anyLong(),
                        anyString()
                )
        ).thenAnswer(invocation -> {
            Path file = invocation.getArgument(1);
            ProcessHandle process = ProcessHandle.current();
            String expectedPrefix = "knot-recording-wav-" + process.pid() + "-" + process.info()
                    .startInstant()
                    .orElseThrow()
                    .toEpochMilli() + "-";
            assertThat(
                    file.getFileName()
                            .toString()
            ).startsWith(expectedPrefix)
                    .matches("knot-recording-wav-[1-9][0-9]*-[0-9]+-[0-9a-f-]{36}\\.tmp");
            assertThat(Files.getPosixFilePermissions(file)).isEqualTo(PosixFilePermissions.fromString("rw-------"));
            bytes.set(Files.readAllBytes(file));
            return new StoredAudioChunk(
                    true,
                    invocation.getArgument(2),
                    "audio/wav",
                    invocation.getArgument(3),
                    "window-v1"
            );
        });
        return bytes;
    }

    private String checksum(byte[] bytes) throws Exception {
        return Base64.getEncoder()
                .encodeToString(
                        MessageDigest.getInstance("SHA-256")
                                .digest(bytes)
                );
    }

    private void assertEmptyDirectory() throws Exception {
        try (java.util.stream.Stream<Path> files = Files.list(directory)) {
            assertThat(files).isEmpty();
        }
    }
}
