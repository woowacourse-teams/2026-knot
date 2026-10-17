package com.knot.ai.transcription.infrastructure;

import com.knot.ai.global.AiServerProperties;
import com.knot.ai.transcription.application.TranscriptionEngine;
import com.knot.ai.transcription.application.TranscriptionMetrics;
import com.knot.ai.transcription.application.command.CreateTranscriptionCommand;
import com.knot.ai.transcription.domain.TranscriptionErrorCode;
import com.knot.ai.transcription.domain.TranscriptionException;
import com.knot.ai.transcription.domain.TranscriptResult;
import java.io.File;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Component
public class HeadlessTranscriptionEngine implements TranscriptionEngine {
    private static final long MAXIMUM_RESULT_BYTES = 268_435_456L;

    private final AiServerProperties properties;
    private final S3AudioDownloader downloader;
    private final JsonMapper mapper;
    private final JsonNode profile;
    private final ProcessSupervisor supervisor;
    private final AudioInspector audioInspector;
    private final TranscriptionMetrics metrics;
    private volatile boolean ready;

    public HeadlessTranscriptionEngine(
            AiServerProperties properties,
            S3AudioDownloader downloader,
            JsonMapper mapper,
            TranscriptionMetrics metrics
    ) throws IOException {
        this.properties = properties;
        this.downloader = downloader;
        this.mapper = mapper;
        this.metrics = metrics;
        profile = mapper.readTree(
                properties.profileFile()
                        .toFile()
        );
        validateProfile();
        Files.createDirectories(properties.workDirectory());
        supervisor = new ProcessSupervisor(
                properties,
                mapper,
                this::markNotReady
        );
        audioInspector = new AudioInspector();
        preflight();
        ready = true;
    }

    @Override
    public boolean isReady() {
        return ready;
    }

    @Override
    public TranscriptResult transcribe(CreateTranscriptionCommand request) throws Exception {
        Path directory = directory(request.attemptId());
        Files.createDirectory(directory);
        Files.setPosixFilePermissions(
                directory,
                PosixFilePermissions.fromString("rwx------")
        );
        Path source = directory.resolve("input.webm");
        Path wav = directory.resolve("input.wav");
        metrics.stage(
                TranscriptionMetrics.Stage.DOWNLOAD,
                request.attemptId(),
                TranscriptionErrorCode.DOWNLOAD_FAILED,
                () -> {
                    downloader.download(
                            request.downloadUrl(),
                            source
                    );
                    return null;
                }
        );
        double duration = metrics.stage(
                TranscriptionMetrics.Stage.DECODE,
                request.attemptId(),
                TranscriptionErrorCode.DECODE_FAILED,
                () -> {
                    supervisor.runDecoding(
                            List.of(
                                    properties.ffmpeg(),
                                    "-nostdin",
                                    "-v",
                                    "error",
                                    "-y",
                                    "-i",
                                    source.toString(),
                                    "-vn",
                                    "-ac",
                                    "1",
                                    "-ar",
                                    "16000",
                                    "-c:a",
                                    "pcm_s16le",
                                    "-t",
                                    Integer.toString(AudioInspector.MAXIMUM_DURATION_SECONDS),
                                    "-fs",
                                    "231448576",
                                    wav.toString()
                            ),
                            directory,
                            properties.conversionTimeout()
                    );
                    return audioInspector.readPcmDuration(wav);
                }
        );
        metrics.stage(
                TranscriptionMetrics.Stage.MODEL,
                request.attemptId(),
                TranscriptionErrorCode.INFERENCE_FAILED,
                () -> {
                    Map<String, Object> manifest = new LinkedHashMap<>();
                    manifest.put(
                            "audioPath",
                            wav.toString()
                    );
                    manifest.put(
                            "outputDirectory",
                            directory.toString()
                    );
                    manifest.put(
                            "profile",
                            profile
                    );
                    if (request.speakerCount() != null) {
                        manifest.put(
                                "speakerCount",
                                request.speakerCount()
                        );
                    }
                    Path input = directory.resolve("input.json");
                    mapper.writeValue(
                            input.toFile(),
                            manifest
                    );
                    supervisor.runInference(
                            List.of(
                                    properties.pnpm(),
                                    "exec",
                                    "tsx",
                                    "scripts/serverPipeline.ts",
                                    "--manifest",
                                    input.toString()
                            ),
                            directory,
                            properties.inferenceTimeout()
                    );
                    return null;
                }
        );
        return metrics.stage(
                TranscriptionMetrics.Stage.RESULT_CHECK,
                request.attemptId(),
                TranscriptionErrorCode.INVALID_RESULT,
                () -> readResult(
                        directory,
                        duration
                )
        );
    }

    private TranscriptResult readResult(
            Path directory,
            double duration
    ) throws IOException {
        Path resultFile = directory.resolve("result.json");
        if (!Files.isRegularFile(resultFile) || Files.size(resultFile) > MAXIMUM_RESULT_BYTES) {
            throw new TranscriptionException(TranscriptionErrorCode.INVALID_RESULT);
        }
        try {
            TranscriptResult result = mapper.readValue(
                    resultFile.toFile(),
                    TranscriptResult.class
            );
            JsonNode raw = mapper.readTree(result.rawWhisperJson());
            if (!raw.isObject() || !raw.path("transcription")
                    .isArray()) {
                throw new TranscriptionException(TranscriptionErrorCode.INVALID_RESULT);
            }
            for (TranscriptResult.Utterance utterance : result.utterances()) {
                if (utterance.endSec() > duration + 1) {
                    throw new TranscriptionException(TranscriptionErrorCode.INVALID_RESULT);
                }
            }
            return result;
        } catch (TranscriptionException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new TranscriptionException(
                    TranscriptionErrorCode.INVALID_RESULT,
                    exception
            );
        }
    }

    @Override
    public void cleanup(UUID attemptId) throws Exception {
        Path directory = directory(attemptId);
        if (Files.isSymbolicLink(directory)) {
            throw new TranscriptionException(TranscriptionErrorCode.UNOWNED_WORK_DIRECTORY);
        }
        if (!Files.exists(directory)) {
            return;
        }
        supervisor.cleanupOwnership(directory);
        try (java.util.stream.Stream<Path> files = Files.walk(directory)) {
            for (Path file : files.sorted(Comparator.reverseOrder())
                    .toList()) {
                Files.delete(file);
            }
        }
    }

    private Path directory(UUID attemptId) {
        return properties.workDirectory()
                .toAbsolutePath()
                .normalize()
                .resolve(attemptId.toString());
    }

    private void preflight() throws IOException {
        try (DirectoryStream<Path> directories = Files.newDirectoryStream(properties.workDirectory())) {
            for (Path directory : directories) {
                if (Files.isSymbolicLink(directory)) {
                    throw new TranscriptionException(TranscriptionErrorCode.UNOWNED_WORK_DIRECTORY);
                }
                if (!Files.isDirectory(
                        directory,
                        LinkOption.NOFOLLOW_LINKS
                )) {
                    continue;
                }
                UUID attemptId;
                try {
                    attemptId = UUID.fromString(
                            directory.getFileName()
                                    .toString()
                    );
                } catch (IllegalArgumentException exception) {
                    throw new TranscriptionException(
                            TranscriptionErrorCode.UNOWNED_WORK_DIRECTORY,
                            exception
                    );
                }
                try {
                    cleanup(attemptId);
                } catch (Exception exception) {
                    throw new TranscriptionException(
                            TranscriptionErrorCode.PROCESS_CLEANUP_FAILED,
                            exception
                    );
                }
            }
        }
    }

    private Path supervisor() {
        return properties.runnerDirectory()
                .resolve("scripts/serverSupervisor.py");
    }

    private void validateProfile() {
        for (String command : List.of(
                properties.python(),
                properties.pnpm(),
                properties.ffmpeg(),
                "node"
        )) {
            if (!hasExecutable(command)) {
                throw new TranscriptionException(TranscriptionErrorCode.MISSING_RUNTIME);
            }
        }
        for (String field : List.of(
                "whisperBinary",
                "diarizeBinary",
                "whisperModel",
                "vadModel",
                "segmentationModel",
                "embeddingModel"
        )) {
            Path asset = Path.of(
                    profile.path(field)
                            .asString()
            );
            if (!asset.isAbsolute() || !Files.isRegularFile(asset) || !Files.isReadable(asset)) {
                throw new TranscriptionException(TranscriptionErrorCode.INVALID_PROFILE);
            }
        }
        if (profile.path("sttThreads")
                .asInt() <= 0
                || profile.path("diarizeThreads")
                        .asInt() <= 0
                || !Files.isRegularFile(supervisor())) {
            throw new TranscriptionException(TranscriptionErrorCode.INVALID_PROFILE);
        }
    }

    private boolean hasExecutable(String command) {
        Path path = Path.of(command);
        if (path.isAbsolute()) {
            return Files.isRegularFile(path) && Files.isExecutable(path);
        }
        String searchPath = System.getenv("PATH");
        if (searchPath == null) {
            return false;
        }
        for (String entry : searchPath.split(File.pathSeparator)) {
            Path candidate = Path.of(entry)
                    .resolve(command);
            if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) {
                return true;
            }
        }
        return false;
    }

    private void markNotReady() {
        ready = false;
    }
}
