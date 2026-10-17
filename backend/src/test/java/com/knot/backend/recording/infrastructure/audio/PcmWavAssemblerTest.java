package com.knot.backend.recording.infrastructure.audio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verifyNoInteractions;

import com.knot.backend.recording.application.RecordingAudioFinalStorage;
import com.knot.backend.recording.application.dto.result.FinalizedAudioFile;
import com.knot.backend.recording.domain.RecordingException;
import com.knot.backend.recording.domain.model.AudioManifestPart;
import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.List;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PcmWavAssemblerTest {
    @TempDir
    Path directory;
    private final RecordingAudioFinalStorage storage = mock(RecordingAudioFinalStorage.class);

    @Test
    void assemble_preservesTailAndSilencesOnlyMissingRange() throws Exception {
        // given
        byte[] first = {1, 2, 3, 4};
        byte[] tail = {5, 6};
        AudioManifestPart a = part(
                1,
                0,
                first
        );
        AudioManifestPart gap = new AudioManifestPart(
                2,
                2,
                2,
                "missing",
                null,
                null
        );
        AudioManifestPart b = part(
                3,
                4,
                tail
        );
        when(storage.openChunk(a)).thenReturn(new ByteArrayInputStream(first));
        when(storage.openChunk(b)).thenReturn(new ByteArrayInputStream(tail));
        PcmWavAssembler assembler = assembler();

        // when
        FinalizedAudioFile result = assembler.assemble(
                List.of(
                        a,
                        gap,
                        b
                ),
                5,
                () -> {
                }
        );

        // then
        Path file = result.path();
        byte[] bytes = Files.readAllBytes(file);
        assertThat(bytes).hasSize(54);
        ByteBuffer header = ByteBuffer.wrap(bytes)
                .order(ByteOrder.LITTLE_ENDIAN);
        assertThat(
                new String(
                        bytes,
                        0,
                        4,
                        java.nio.charset.StandardCharsets.US_ASCII
                )
        ).isEqualTo("RIFF");
        assertThat(header.getInt(4)).isEqualTo(46);
        assertThat(header.getShort(20)).isEqualTo((short) 1);
        assertThat(header.getShort(22)).isEqualTo((short) 1);
        assertThat(header.getInt(24)).isEqualTo(16000);
        assertThat(header.getInt(28)).isEqualTo(32000);
        assertThat(header.getShort(32)).isEqualTo((short) 2);
        assertThat(header.getShort(34)).isEqualTo((short) 16);
        assertThat(header.getInt(40)).isEqualTo(10);
        assertThat(
                java.util.Arrays.copyOfRange(
                        bytes,
                        44,
                        bytes.length
                )
        ).containsExactly(
                1,
                2,
                3,
                4,
                0,
                0,
                0,
                0,
                5,
                6
        );
        assertThat(result.checksumSha256()).isEqualTo(checksum(bytes));
        assertThat(Files.getPosixFilePermissions(file)).isEqualTo(PosixFilePermissions.fromString("rw-------"));
        result.close();
        assertThat(file).doesNotExist();
    }

    @Test
    void assemble_rejectsCorruptionAndDeletesTemporaryFile() throws Exception {
        // given
        AudioManifestPart part = part(
                1,
                0,
                new byte[]{1, 2}
        );
        when(storage.openChunk(part)).thenReturn(new ByteArrayInputStream(new byte[]{9, 9}));

        // when
        Throwable failure = catchThrowable(
                () -> assembler().assemble(
                        List.of(part),
                        1,
                        () -> {
                        }
                )
        );

        // then
        assertThat(failure).isInstanceOf(RecordingException.class);
        try (java.util.stream.Stream<Path> files = Files.list(directory)) {
            assertThat(files).isEmpty();
        }
    }

    @Test
    void assemble_rejectsInsufficientDiskBeforeReading() throws Exception {
        // given
        AudioManifestPart part = part(
                1,
                0,
                new byte[]{1, 2}
        );
        PcmWavAssembler assembler = new PcmWavAssembler(
                storage,
                directory,
                () -> 512L * 1024 * 1024 - 1
        );

        // when
        Throwable failure = catchThrowable(
                () -> assembler.assemble(
                        List.of(part),
                        1,
                        () -> {
                        }
                )
        );

        // then
        assertThat(failure).isInstanceOf(RecordingException.class);
        verifyNoInteractions(storage);
        try (java.util.stream.Stream<Path> files = Files.list(directory)) {
            assertThat(files).isEmpty();
        }
    }

    @Test
    void assemble_checksLeaseDuringStreamingAndCleansFile() throws Exception {
        // given
        byte[] bytes = new byte[160000];
        AudioManifestPart part = part(
                1,
                0,
                bytes
        );
        when(storage.openChunk(part)).thenReturn(new ByteArrayInputStream(bytes));
        java.util.concurrent.atomic.AtomicInteger count = new java.util.concurrent.atomic.AtomicInteger();
        Runnable checkpoint = () -> {
            if (count.incrementAndGet() == 5) {
                throw new IllegalStateException("임대 만료");
            }
        };

        // when
        Throwable failure = catchThrowable(
                () -> assembler().assemble(
                        List.of(part),
                        80000,
                        checkpoint
                )
        );

        // then
        assertThat(failure).isInstanceOf(IllegalStateException.class)
                .hasMessage("임대 만료");
        try (java.util.stream.Stream<Path> files = Files.list(directory)) {
            assertThat(files).isEmpty();
        }
    }

    @Test
    void assemble_streamsTwoHoursWithoutAccumulatingAudioInHeap() throws Exception {
        // given
        List<AudioManifestPart> parts = new ArrayList<>();
        for (int index = 0; index < 1440; index++) {
            parts.add(
                    new AudioManifestPart(
                            index + 1,
                            index * 80000L,
                            80000,
                            "missing",
                            null,
                            null
                    )
            );
        }

        // when
        FinalizedAudioFile result = assembler().assemble(
                parts,
                115200000,
                () -> {
                }
        );

        // then
        try (result) {
            assertThat(Files.size(result.path())).isEqualTo(230400044L);
            assertThat(result.byteLength()).isEqualTo(230400044L);
            try (java.io.InputStream input = Files.newInputStream(result.path())) {
                assertThat(input.skip(230400043L)).isEqualTo(230400043L);
                assertThat(input.read()).isZero();
                assertThat(input.read()).isEqualTo(-1);
            }
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints = {0, 1, 3, 4})
    void assemble_rejectsTruncatedOrOversizedChunk(int length) throws Exception {
        // given
        AudioManifestPart part = part(
                1,
                0,
                new byte[]{1, 2}
        );
        when(storage.openChunk(part)).thenReturn(new ByteArrayInputStream(new byte[length]));

        // when
        Throwable failure = catchThrowable(
                () -> assembler().assemble(
                        List.of(part),
                        1,
                        () -> {
                        }
                )
        );

        // then
        assertThat(failure).isInstanceOf(RecordingException.class);
        try (java.util.stream.Stream<Path> files = Files.list(directory)) {
            assertThat(files).isEmpty();
        }
    }

    @Test
    void assemble_failsCleanlyWhenTemporaryFileCannotBeCreated() throws Exception {
        // given
        AudioManifestPart part = part(
                1,
                0,
                new byte[]{1, 2}
        );
        Path invalidDirectory = Files.write(
                directory.resolve("regular-file"),
                new byte[0]
        );
        PcmWavAssembler assembler = new PcmWavAssembler(
                storage,
                invalidDirectory,
                () -> Long.MAX_VALUE
        );

        // when
        Throwable failure = catchThrowable(
                () -> assembler.assemble(
                        List.of(part),
                        1,
                        () -> {
                        }
                )
        );

        // then
        assertThat(failure).isInstanceOf(RecordingException.class);
        verifyNoInteractions(storage);
        assertThat(invalidDirectory).isRegularFile();
    }

    private PcmWavAssembler assembler() {
        return new PcmWavAssembler(
                storage,
                directory,
                () -> Long.MAX_VALUE
        );
    }

    private AudioManifestPart part(
            int sequence,
            long offset,
            byte[] bytes
    ) throws Exception {
        return new AudioManifestPart(
                sequence,
                offset,
                bytes.length / 2,
                "chunk-" + sequence,
                checksum(bytes),
                "v1"
        );
    }

    private String checksum(byte[] bytes) throws Exception {
        return Base64.getEncoder()
                .encodeToString(
                        MessageDigest.getInstance("SHA-256")
                                .digest(bytes)
                );
    }
}
