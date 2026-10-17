package com.knot.ai.transcription.infrastructure;

import com.knot.ai.transcription.domain.TranscriptionErrorCode;
import com.knot.ai.transcription.domain.TranscriptionException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;

class AudioInspector {
    static final int MAXIMUM_DURATION_SECONDS = 7200;
    private static final long MAXIMUM_PCM_BYTES = 230_400_000L;
    private static final long WAV_HEADER_ALLOWANCE_BYTES = 1_048_576L;
    private static final int RIFF_CHUNK_ID = 0x52494646;
    private static final int WAVE_FORMAT_ID = 0x57415645;
    private static final int FORMAT_CHUNK_ID = 0x666d7420;
    private static final int DATA_CHUNK_ID = 0x64617461;

    double readPcmDuration(Path wav) throws IOException {
        return readPcmDuration(
                wav,
                () -> {
                }
        );
    }

    double readPcmDuration(
            Path wav,
            Runnable checkpoint
    ) throws IOException {
        checkpoint.run();
        if (Files.size(wav) > MAXIMUM_PCM_BYTES + WAV_HEADER_ALLOWANCE_BYTES) {
            throw new TranscriptionException(TranscriptionErrorCode.AUDIO_DURATION_EXCEEDED);
        }
        double duration;
        try (RandomAccessFile file = new RandomAccessFile(
                wav.toFile(),
                "r"
        )) {
            checkpoint.run();
            if (file.length() < 44 || file.readInt() != RIFF_CHUNK_ID) {
                throw new TranscriptionException(TranscriptionErrorCode.INVALID_AUDIO_FORMAT);
            }
            long riffSize = Integer.toUnsignedLong(Integer.reverseBytes(file.readInt()));
            if (riffSize != file.length() - 8) {
                throw new TranscriptionException(TranscriptionErrorCode.INVALID_AUDIO_FORMAT);
            }
            if (file.readInt() != WAVE_FORMAT_ID) {
                throw new TranscriptionException(TranscriptionErrorCode.INVALID_AUDIO_FORMAT);
            }
            duration = readChunks(
                    file,
                    checkpoint
            );
        }
        checkpoint.run();
        if (duration <= 0 || duration > MAXIMUM_DURATION_SECONDS) {
            throw new TranscriptionException(TranscriptionErrorCode.AUDIO_DURATION_EXCEEDED);
        }
        return duration;
    }

    private double readChunks(
            RandomAccessFile file,
            Runnable checkpoint
    ) throws IOException {
        boolean formatVerified = false;
        Double duration = null;
        while (file.getFilePointer() + 8 <= file.length()) {
            checkpoint.run();
            int chunkId = file.readInt();
            long chunkSize = Integer.toUnsignedLong(Integer.reverseBytes(file.readInt()));
            long position = file.getFilePointer();
            if (chunkSize + chunkSize % 2 > file.length() - position) {
                throw new TranscriptionException(TranscriptionErrorCode.INVALID_AUDIO_FORMAT);
            }
            if (chunkId == FORMAT_CHUNK_ID) {
                if (formatVerified || duration != null) {
                    throw new TranscriptionException(TranscriptionErrorCode.INVALID_AUDIO_FORMAT);
                }
                verifyFormatChunk(
                        file,
                        chunkSize
                );
                formatVerified = true;
            } else if (chunkId == DATA_CHUNK_ID) {
                if (duration != null) {
                    throw new TranscriptionException(TranscriptionErrorCode.INVALID_AUDIO_FORMAT);
                }
                duration = readDataDuration(
                        chunkSize,
                        formatVerified
                );
            }
            file.seek(position + chunkSize + (chunkSize % 2));
        }
        if (duration == null || file.getFilePointer() != file.length()) {
            throw new TranscriptionException(TranscriptionErrorCode.INVALID_AUDIO_FORMAT);
        }
        return duration;
    }

    private void verifyFormatChunk(
            RandomAccessFile file,
            long chunkSize
    ) throws IOException {
        if (chunkSize < 16 || Short.reverseBytes(file.readShort()) != 1 || Short.reverseBytes(file.readShort()) != 1
                || Integer.reverseBytes(file.readInt()) != 16000 || Integer.reverseBytes(file.readInt()) != 32000
                || Short.reverseBytes(file.readShort()) != 2 || Short.reverseBytes(file.readShort()) != 16) {
            throw new TranscriptionException(TranscriptionErrorCode.INVALID_AUDIO_FORMAT);
        }
    }

    private double readDataDuration(
            long chunkSize,
            boolean formatVerified
    ) {
        if (!formatVerified || chunkSize % 2 != 0) {
            throw new TranscriptionException(TranscriptionErrorCode.INVALID_AUDIO_FORMAT);
        }
        if (chunkSize > MAXIMUM_PCM_BYTES) {
            throw new TranscriptionException(TranscriptionErrorCode.AUDIO_DURATION_EXCEEDED);
        }
        return chunkSize / 32000.0;
    }
}
