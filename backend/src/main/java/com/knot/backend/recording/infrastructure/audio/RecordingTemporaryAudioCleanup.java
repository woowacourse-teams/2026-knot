package com.knot.backend.recording.infrastructure.audio;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.UserPrincipal;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class RecordingTemporaryAudioCleanup {
    private static final Pattern OWNED_NAME = Pattern.compile(
            "^knot-recording-wav-([1-9][0-9]*)-([0-9]+)-[0-9a-f]{8}-[0-9a-f]{4}"
                    + "-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.tmp$"
    );
    private static final Set<PosixFilePermission> OWNER_ONLY = Set.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE
    );

    @EventListener(ApplicationReadyEvent.class)
    public void cleanAfterStartup() {
        Path directory = Path.of(System.getProperty("java.io.tmpdir"));
        try {
            UserPrincipal owner = directory.getFileSystem()
                    .getUserPrincipalLookupService()
                    .lookupPrincipalByName(System.getProperty("user.name"));
            int removed = cleanDirectory(
                    directory,
                    owner
            );
            log.info(
                    "녹음 임시 파일 재시작 정리 count={}",
                    removed
            );
        } catch (IOException | DirectoryIteratorException | UnsupportedOperationException
                | SecurityException exception) {
            log.warn("녹음 임시 파일 재시작 정리를 완료하지 못했습니다");
        }
    }

    int cleanDirectory(
            Path directory,
            UserPrincipal owner
    ) throws IOException {
        int removed = 0;
        try (DirectoryStream<Path> files = Files.newDirectoryStream(
                directory,
                "knot-recording-wav-*.tmp"
        )) {
            for (Path file : files) {
                removed += deleteStaleFile(
                        file,
                        owner
                );
            }
        }
        return removed;
    }

    private int deleteStaleFile(
            Path file,
            UserPrincipal owner
    ) {
        try {
            if (!isOwnedFile(
                    file,
                    owner
            ) || !isPreviousProcess(file.getFileName()
                    .toString()
            )) {
                return 0;
            }
            if (Files.deleteIfExists(file)) {
                return 1;
            }
        } catch (IOException | UnsupportedOperationException | SecurityException exception) {
            log.warn("녹음 임시 파일 정리를 다음 재시작까지 보류합니다");
        }
        return 0;
    }

    private boolean isOwnedFile(
            Path file,
            UserPrincipal owner
    ) throws IOException {
        if (!Files.isRegularFile(
                file,
                LinkOption.NOFOLLOW_LINKS
        )) {
            return false;
        }
        UserPrincipal actualOwner = Files.getOwner(
                file,
                LinkOption.NOFOLLOW_LINKS
        );
        if (!actualOwner.equals(owner)) {
            return false;
        }
        return Files.getPosixFilePermissions(
                file,
                LinkOption.NOFOLLOW_LINKS
        )
                .equals(OWNER_ONLY);
    }

    private boolean isPreviousProcess(String name) {
        Matcher matcher = OWNED_NAME.matcher(name);
        if (!matcher.matches()) {
            return false;
        }
        try {
            long pid = Long.parseLong(matcher.group(1));
            long expectedStart = Long.parseLong(matcher.group(2));
            Optional<ProcessHandle> process = ProcessHandle.of(pid);
            if (process.isEmpty()) {
                return true;
            }
            Optional<Instant> actualStart = process.orElseThrow()
                    .info()
                    .startInstant();
            return actualStart.isPresent() && actualStart.orElseThrow()
                    .toEpochMilli() != expectedStart;
        } catch (NumberFormatException | SecurityException exception) {
            return false;
        }
    }
}
