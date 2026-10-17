package com.knot.ai.transcription.application;

import com.knot.ai.transcription.domain.TranscriptionErrorCode;
import com.knot.ai.transcription.domain.TranscriptionException;
import java.util.UUID;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.spi.LoggingEventBuilder;
import tools.jackson.databind.json.JsonMapper;

/**
 * Approved transcription fields only; never attach exception messages or
 * payloads.
 */
public final class TranscriptionLog {
    private static final Logger LOGGER = LoggerFactory.getLogger(TranscriptionLog.class);
    private static final JsonMapper MAPPER = JsonMapper.builder()
            .build();

    private TranscriptionLog() {}

    public static long start() {
        return System.nanoTime();
    }

    public static void record(
            String event,
            String outcome,
            UUID attemptId,
            long started,
            String errorCode,
            Exception exception
    ) {
        LoggingEventBuilder log = "failed".equals(outcome)
                ? (exception instanceof TranscriptionException ? LOGGER.atWarn() : LOGGER.atError())
                : LOGGER.atInfo();
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put(
                "event",
                event
        );
        fields.put(
                "outcome",
                outcome
        );
        fields.put(
                "attemptId",
                attemptId.toString()
        );
        fields.put(
                "elapsedMs",
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
        );
        if (errorCode != null) {
            fields.put(
                    "errorCode",
                    errorCode
            );
        }
        if (exception != null) {
            fields.put(
                    "exception",
                    exception.getClass()
                            .getName()
            );
            if (exception.getCause() != null) {
                fields.put(
                        "cause",
                        exception.getCause()
                                .getClass()
                                .getName()
                );
            }
            StackTraceElement[] stack = exception.getStackTrace();
            if (stack.length > 0) {
                fields.put(
                        "location",
                        stack[0].getClassName() + "." + stack[0].getMethodName() + ":" + stack[0].getLineNumber()
                );
            }
        }
        fields.forEach(log::addKeyValue);
        String message;
        try {
            message = MAPPER.writeValueAsString(fields);
        } catch (RuntimeException ignored) {
            message = "Recording transcription event";
        }
        log.log(message);
    }

    public static <T> T stage(
            String event,
            UUID attemptId,
            TranscriptionErrorCode fallback,
            Action<T> action
    ) throws Exception {
        long started = start();
        record(
                event,
                "started",
                attemptId,
                started,
                null,
                null
        );
        try {
            T result = action.run();
            record(
                    event,
                    "completed",
                    attemptId,
                    started,
                    null,
                    null
            );
            return result;
        } catch (Exception exception) {
            record(
                    event,
                    "failed",
                    attemptId,
                    started,
                    code(
                            exception,
                            fallback
                    ),
                    exception
            );
            throw exception;
        }
    }

    public static String code(
            Exception exception,
            TranscriptionErrorCode fallback
    ) {
        return exception instanceof TranscriptionException failure ? failure.getCode() : fallback.getCode();
    }

    @FunctionalInterface
    public interface Action<T> {
        T run() throws Exception;
    }
}
