package com.knot.backend.recording.infrastructure.audio;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RecordingTemporaryAudioCleanupTest {
    @TempDir
    Path directory;
    private final RecordingTemporaryAudioCleanup cleanup = new RecordingTemporaryAudioCleanup();

    @Test
    void removesOwnedPrivateFileOfMissingProcess() throws Exception {
        // given
        Path file = privateFile(
                Long.MAX_VALUE,
                1
        );
        // when
        int removed = cleanup.cleanDirectory(
                directory,
                Files.getOwner(file)
        );
        // then
        assertThat(removed).isEqualTo(1);
        assertThat(file).doesNotExist();
    }

    @Test
    void preservesFileOfActiveOwnerProcess() throws Exception {
        // given
        ProcessHandle process = ProcessHandle.current();
        Path file = privateFile(
                process.pid(),
                process.info()
                        .startInstant()
                        .orElseThrow()
                        .toEpochMilli()
        );
        // when
        int removed = cleanup.cleanDirectory(
                directory,
                Files.getOwner(file)
        );
        // then
        assertThat(removed).isZero();
        assertThat(file).exists();
    }

    @Test
    void reusedPidWithDifferentKnownStartProvesOldProcess() throws Exception {
        // given
        ProcessHandle process = ProcessHandle.current();
        Path file = privateFile(
                process.pid(),
                process.info()
                        .startInstant()
                        .orElseThrow()
                        .toEpochMilli() - 1
        );
        // when
        int removed = cleanup.cleanDirectory(
                directory,
                Files.getOwner(file)
        );
        // then
        assertThat(removed).isEqualTo(1);
        assertThat(file).doesNotExist();
    }

    @Test
    void ignoresUnknownLegacyNameAndSymbolicLink() throws Exception {
        // given
        Path original = Files.createFile(directory.resolve("knot-recording-wav-old.tmp"));
        Files.setPosixFilePermissions(
                original,
                PosixFilePermissions.fromString("rw-------")
        );
        Path symbolic = directory.resolve("knot-recording-wav-9223372036854775807-1-" + UUID.randomUUID() + ".tmp");
        Files.createSymbolicLink(
                symbolic,
                original
        );
        // when
        int removed = cleanup.cleanDirectory(
                directory,
                Files.getOwner(original)
        );
        // then
        assertThat(removed).isZero();
        assertThat(original).exists();
        assertThat(Files.isSymbolicLink(symbolic)).isTrue();
    }

    @Test
    void ignoresFilesWithBroaderPermissionsOrDifferentOwner() throws Exception {
        // given
        Path file = privateFile(
                Long.MAX_VALUE,
                1
        );
        Files.setPosixFilePermissions(
                file,
                PosixFilePermissions.fromString("rw-r--r--")
        );
        UserPrincipal other = () -> "다른 소유자";
        // when
        int removed = cleanup.cleanDirectory(
                directory,
                other
        );
        // then
        assertThat(removed).isZero();
        assertThat(file).exists();
    }

    @Test
    void ignoresPrivateLookingFileWithBroaderPermissions() throws Exception {
        // given
        Path file = privateFile(
                Long.MAX_VALUE,
                1
        );
        Files.setPosixFilePermissions(
                file,
                PosixFilePermissions.fromString("rw-r--r--")
        );
        // when
        int removed = cleanup.cleanDirectory(
                directory,
                Files.getOwner(file)
        );
        // then
        assertThat(removed).isZero();
        assertThat(file).exists();
    }

    private Path privateFile(
            long pid,
            long start
    ) throws Exception {
        return Files.createFile(
                directory.resolve("knot-recording-wav-" + pid + "-" + start + "-" + UUID.randomUUID() + ".tmp"),
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))
        );
    }
}
