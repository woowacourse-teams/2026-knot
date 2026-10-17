package com.knot.ai.transcription.infrastructure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.knot.ai.transcription.domain.TranscriptionErrorCode;
import com.knot.ai.transcription.domain.TranscriptionException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AudioInspectorTest {
    @TempDir
    Path directory;

    @Test
    @DisplayName("WAV chunk 순회 중 취소되면 남은 chunk를 검사하지 않는다")
    void readPcmDuration_failure_canceledDuringScan() throws Exception {
        // given
        Path wav = writeWav();
        AudioInspector inspector = new AudioInspector();
        AtomicInteger checkpoints = new AtomicInteger();
        Runnable checkpoint = () -> {
            if (checkpoints.incrementAndGet() == 5) {
                Thread.currentThread()
                        .interrupt();
            }
            if (Thread.currentThread()
                    .isInterrupted()) {
                throw new TranscriptionException(TranscriptionErrorCode.CANCELED);
            }
        };
        try {
            // when
            TranscriptionException exception = assertThrows(
                    TranscriptionException.class,
                    () -> inspector.readPcmDuration(
                            wav,
                            checkpoint
                    )
            );

            // then
            assertEquals(
                    "CANCELED",
                    exception.getCode()
            );
            assertEquals(
                    5,
                    checkpoints.get()
            );
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    @DisplayName("WAV chunk 순회 중 마감에 도달하면 즉시 시간 초과를 전달한다")
    void readPcmDuration_failure_deadlineDuringScan() throws Exception {
        // given
        Path wav = writeWav();
        AudioInspector inspector = new AudioInspector();
        AtomicInteger checkpoints = new AtomicInteger();
        AtomicLong elapsedNanos = new AtomicLong();
        Runnable checkpoint = () -> {
            checkpoints.incrementAndGet();
            if (elapsedNanos.incrementAndGet() >= 5) {
                throw new TranscriptionException(TranscriptionErrorCode.DOWNLOAD_TIMEOUT);
            }
        };

        // when
        TranscriptionException exception = assertThrows(
                TranscriptionException.class,
                () -> inspector.readPcmDuration(
                        wav,
                        checkpoint
                )
        );

        // then
        assertEquals(
                "DOWNLOAD_TIMEOUT",
                exception.getCode()
        );
        assertEquals(
                5,
                checkpoints.get()
        );
    }

    @Test
    @DisplayName("취소 확인은 파일 크기나 헤더 조회보다 먼저 실행한다")
    void readPcmDuration_failure_checkpointBeforeFileAccess() {
        // given
        AudioInspector inspector = new AudioInspector();
        Path missingWav = directory.resolve("missing.wav");
        Runnable checkpoint = () -> {
            throw new TranscriptionException(TranscriptionErrorCode.CANCELED);
        };

        // when
        TranscriptionException exception = assertThrows(
                TranscriptionException.class,
                () -> inspector.readPcmDuration(
                        missingWav,
                        checkpoint
                )
        );

        // then
        assertEquals(
                "CANCELED",
                exception.getCode()
        );
    }

    private Path writeWav() throws Exception {
        ByteBuffer bytes = ByteBuffer.allocate(108)
                .order(ByteOrder.LITTLE_ENDIAN);
        bytes.put("RIFF".getBytes(StandardCharsets.US_ASCII));
        bytes.putInt(100);
        bytes.put("WAVEfmt ".getBytes(StandardCharsets.US_ASCII));
        bytes.putInt(16);
        bytes.putShort((short) 1);
        bytes.putShort((short) 1);
        bytes.putInt(16000);
        bytes.putInt(32000);
        bytes.putShort((short) 2);
        bytes.putShort((short) 16);
        for (int i = 0; i < 4; i++) {
            bytes.put("JUNK".getBytes(StandardCharsets.US_ASCII));
            bytes.putInt(0);
        }
        bytes.put("data".getBytes(StandardCharsets.US_ASCII));
        bytes.putInt(32);
        bytes.put(new byte[32]);
        Path wav = directory.resolve("input.wav");
        Files.write(
                wav,
                bytes.array()
        );
        return wav;
    }
}
