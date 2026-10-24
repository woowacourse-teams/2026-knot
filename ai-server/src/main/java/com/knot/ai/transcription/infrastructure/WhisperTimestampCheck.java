package com.knot.ai.transcription.infrastructure;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * VAD를 쓰는 프로필의 whisper-cli가 원음 기준 token 시각을 출력하는 패치 빌드인지 시작할 때 확인한다. 패치 없는 CLI는
 * VAD로 압축된 시각을 출력하므로 runner가 모든 VAD 결과를 거절한다. 인자는 runner의 buildWhisperArgs와 같은
 * 순서를 쓴다.
 */
class WhisperTimestampCheck {
    private static final Logger LOGGER = LoggerFactory.getLogger(WhisperTimestampCheck.class);
    private static final Duration TIMEOUT = Duration.ofMinutes(2);
    private static final int SAMPLE_RATE = 16_000;
    private static final int SILENCE_SECONDS = 1;

    private final JsonMapper mapper;

    WhisperTimestampCheck(JsonMapper mapper) {
        this.mapper = mapper;
    }

    boolean restoresOriginalTimestamps(JsonNode profile) throws IOException {
        if (vadDisabled(profile)) {
            return true;
        }
        Path directory = Files.createTempDirectory("knot-whisper-check");
        try {
            boolean restored = exportsOriginalTimestamps(
                    profile,
                    directory
            );
            if (!restored) {
                LOGGER.warn("whisper_timestamp_check failed: VAD token timestamps are not restored");
            }
            return restored;
        } finally {
            delete(directory);
        }
    }

    private boolean vadDisabled(JsonNode profile) {
        JsonNode enabled = profile.path("whisperVadEnabled");
        return enabled.isBoolean() && !enabled.booleanValue();
    }

    private boolean exportsOriginalTimestamps(
            JsonNode profile,
            Path directory
    ) throws IOException {
        Path audio = directory.resolve("silence.wav");
        Files.write(
                audio,
                silence()
        );
        Path prefix = directory.resolve("whisper");
        if (!completed(
                command(
                        profile,
                        audio,
                        prefix
                )
        )) {
            return false;
        }
        return marked(directory.resolve("whisper.json"));
    }

    private ProcessBuilder command(
            JsonNode profile,
            Path audio,
            Path prefix
    ) {
        return new ProcessBuilder(
                List.of(
                        text(
                                profile,
                                "whisperBinary"
                        ),
                        "-m",
                        text(
                                profile,
                                "whisperModel"
                        ),
                        "-f",
                        audio.toString(),
                        "-l",
                        "ko",
                        "-t",
                        String.valueOf(
                                profile.path("sttThreads")
                                        .asInt()
                        ),
                        "--output-json-full",
                        "-of",
                        prefix.toString(),
                        "--no-prints",
                        "--vad",
                        "--vad-model",
                        text(
                                profile,
                                "vadModel"
                        )
                )
        ).redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD);
    }

    private boolean completed(ProcessBuilder command) throws IOException {
        Process process = command.start();
        try {
            if (!process.waitFor(
                    TIMEOUT.toMillis(),
                    TimeUnit.MILLISECONDS
            )) {
                process.destroyForcibly();
                return false;
            }
            return process.exitValue() == 0;
        } catch (InterruptedException exception) {
            process.destroyForcibly();
            Thread.currentThread()
                    .interrupt();
            return false;
        }
    }

    private boolean marked(Path output) {
        if (!Files.isRegularFile(output)) {
            return false;
        }
        try {
            JsonNode params = mapper.readTree(output.toFile())
                    .path("params");
            return params.path("vad")
                    .isBoolean()
                    && params.path("vad")
                            .booleanValue()
                    && "original".equals(
                            params.path("token_timestamps")
                                    .asString()
                    );
        } catch (JacksonException exception) {
            return false;
        }
    }

    private String text(
            JsonNode profile,
            String field
    ) {
        return profile.path(field)
                .asString();
    }

    private byte[] silence() {
        int dataBytes = SAMPLE_RATE * SILENCE_SECONDS * Short.BYTES;
        return ByteBuffer.allocate(44 + dataBytes)
                .order(ByteOrder.LITTLE_ENDIAN)
                .put("RIFF".getBytes())
                .putInt(36 + dataBytes)
                .put("WAVEfmt ".getBytes())
                .putInt(16)
                .putShort((short) 1)
                .putShort((short) 1)
                .putInt(SAMPLE_RATE)
                .putInt(SAMPLE_RATE * Short.BYTES)
                .putShort((short) Short.BYTES)
                .putShort((short) Short.SIZE)
                .put("data".getBytes())
                .putInt(dataBytes)
                .array();
    }

    private void delete(Path directory) throws IOException {
        try (Stream<Path> paths = Files.walk(directory)) {
            for (Path path : paths.sorted(Comparator.reverseOrder())
                    .toList()) {
                Files.deleteIfExists(path);
            }
        }
    }
}
