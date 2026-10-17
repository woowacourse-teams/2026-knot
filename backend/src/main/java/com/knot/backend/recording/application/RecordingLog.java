package com.knot.backend.recording.application;

import com.knot.backend.global.exception.ErrorCategory;
import com.knot.backend.global.exception.ProjectException;
import com.knot.backend.global.logging.SafeExceptionDiagnostics;
import com.knot.backend.recording.domain.RecordingErrorCode;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.slf4j.event.Level;
import org.slf4j.spi.LoggingEventBuilder;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;

public final class RecordingLog {
    private static final Logger LOG = LoggerFactory.getLogger(RecordingLog.class);
    private static final JsonMapper JSON = JsonMapper.builder()
            .build();
    private static final Set<String> SAFE_CODES = Stream.concat(
            Arrays.stream(RecordingErrorCode.values())
                    .map(RecordingErrorCode::getCode),
            Stream.of(
                    "BUSY",
                    "NOT_READY",
                    "JOB_NOT_FOUND",
                    "EXECUTOR_REJECTED",
                    "INFERENCE_FAILED",
                    "CANCELED",
                    "INVALID_RESULT",
                    "AUDIO_DURATION_EXCEEDED",
                    "INVALID_AUDIO_FORMAT",
                    "INVALID_PROFILE",
                    "MISSING_RUNTIME",
                    "PROCESS_CLEANUP_FAILED",
                    "UNOWNED_WORK_DIRECTORY",
                    "DECODE_TIMEOUT",
                    "INFERENCE_TIMEOUT",
                    "DECODE_FAILED",
                    "AUDIO_TOO_LARGE",
                    "DOWNLOAD_FAILED",
                    "DOWNLOAD_TIMEOUT",
                    "INCOMPLETE_DOWNLOAD",
                    "INVALID_AUDIO_METADATA",
                    "INVALID_DOWNLOAD_URL",
                    "STT_FAILED",
                    "DIARIZATION_FAILED",
                    "DIARIZATION_EMPTY",
                    "OUTPUT_LIMIT",
                    "INVALID_MANIFEST",
                    "INVALID_ARGUMENTS",
                    "RUNNER_FAILED",
                    "SERVER_RESTARTED",
                    "RECORDING_UNAVAILABLE",
                    "RESULT_STORAGE_FAILED"
            )
    )
            .collect(Collectors.toUnmodifiableSet());

    private RecordingLog() {}

    public static Scope begin(
            String event,
            long workspaceId,
            Long recordingId,
            Long uploadId,
            UUID attemptId
    ) {
        return new Scope(
                event,
                workspaceId,
                recordingId,
                uploadId,
                attemptId
        );
    }

    public static void committed(
            String event,
            String outcome,
            long workspaceId,
            long recordingId,
            Long uploadId,
            UUID attemptId,
            String endReason
    ) {
        Scope scope = new Scope(
                event,
                workspaceId,
                recordingId,
                uploadId,
                attemptId,
                false
        );
        scope.outcome(outcome)
                .endReason(endReason);
        scope.close();
    }

    public static final class Scope implements AutoCloseable {
        private final String event;
        private final long workspaceId;
        private final String requestId = MDC.get("requestId");
        private final long startedNanos = System.nanoTime();
        private final boolean transactional;
        private final boolean observed;
        private Long recordingId;
        private Long uploadId;
        private UUID attemptId;
        private String outcome;
        private String errorCode;
        private String endReason;
        private Throwable failure;
        private boolean closed;

        private Scope(
                String event,
                long workspaceId,
                Long recordingId,
                Long uploadId,
                UUID attemptId
        ) {
            this(
                    event,
                    workspaceId,
                    recordingId,
                    uploadId,
                    attemptId,
                    true
            );
        }

        private Scope(
                String event,
                long workspaceId,
                Long recordingId,
                Long uploadId,
                UUID attemptId,
                boolean observed
        ) {
            this.event = event;
            this.workspaceId = workspaceId;
            this.recordingId = recordingId;
            this.uploadId = uploadId;
            this.attemptId = attemptId;
            this.observed = observed;
            transactional = TransactionSynchronizationManager.isActualTransactionActive()
                    && TransactionSynchronizationManager.isSynchronizationActive();
            if (observed) {
                emit("started");
            }
            if (transactional) {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void afterCompletion(int status) {
                        if (!observed && status != STATUS_COMMITTED) {
                            return;
                        }
                        if (status == STATUS_UNKNOWN) {
                            outcome = "unconfirmed";
                            errorCode = "TRANSACTION_UNCONFIRMED";
                        } else if (status == STATUS_ROLLED_BACK && !"rejected".equals(outcome)
                                && !"failed".equals(outcome)) {
                            outcome = "failed";
                            errorCode = "TRANSACTION_ROLLED_BACK";
                        }
                        emit(outcome == null ? "unconfirmed" : outcome);
                    }
                });
            }
        }

        public Scope outcome(String outcome) {
            this.outcome = outcome;
            return this;
        }
        public Scope recording(long id) {
            recordingId = id;
            return this;
        }
        public Scope upload(long id) {
            uploadId = id;
            return this;
        }
        public Scope attempt(UUID id) {
            attemptId = id;
            return this;
        }
        public Scope endReason(String reason) {
            endReason = reason;
            return this;
        }
        public Scope error(String code) {
            errorCode = code == null ? null : SAFE_CODES.contains(code) ? code : "UNKNOWN_FAILURE";
            return this;
        }

        public <T> T call(Supplier<T> action) {
            try {
                return action.get();
            } catch (RuntimeException exception) {
                failure = exception;
                if (exception instanceof ProjectException project) {
                    if (project.getErrorCode() instanceof Enum<?>) {
                        errorCode = project.getErrorCode()
                                .getCode();
                    } else {
                        error("UNKNOWN_FAILURE");
                    }
                    if (!"unconfirmed".equals(outcome)) {
                        outcome = project.getErrorCode()
                                .getCategory() == ErrorCategory.INTERNAL_SERVER_ERROR ? "failed" : "rejected";
                    }
                } else {
                    if (!"unconfirmed".equals(outcome)) {
                        outcome = "failed";
                    }
                    errorCode = "INTERNAL_SERVER_ERROR";
                }
                throw exception;
            } finally {
                close();
            }
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            if (!transactional) {
                emit(outcome == null ? "unconfirmed" : outcome);
            }
        }

        private void emit(String result) {
            Map<String, Object> fields = new LinkedHashMap<>();
            fields.put(
                    "event",
                    event
            );
            fields.put(
                    "outcome",
                    result
            );
            fields.put(
                    "requestId",
                    requestId
            );
            fields.put(
                    "workspaceId",
                    workspaceId
            );
            fields.put(
                    "recordingId",
                    recordingId
            );
            fields.put(
                    "uploadId",
                    uploadId
            );
            fields.put(
                    "attemptId",
                    attemptId == null ? null : attemptId.toString()
            );
            if (errorCode != null) {
                fields.put(
                        "errorCode",
                        errorCode
                );
            }
            if (endReason != null) {
                fields.put(
                        "endReason",
                        endReason
                );
            }
            if (observed && !"started".equals(result)) {
                fields.put(
                        "elapsedMs",
                        TimeUnit.NANOSECONDS.toMillis(
                                Math.max(
                                        0,
                                        System.nanoTime() - startedNanos
                                )
                        )
                );
            }
            if (failure != null && "failed".equals(result)) {
                fields.put(
                        "diagnostic",
                        SafeExceptionDiagnostics.describe(failure)
                );
            }
            Level level = "failed".equals(result)
                    ? Level.ERROR
                    : "unconfirmed".equals(result) ? Level.WARN : Level.INFO;
            try {
                LoggingEventBuilder builder = LOG.atLevel(level);
                fields.forEach(builder::addKeyValue);
                builder.log(JSON.writeValueAsString(fields));
            } catch (RuntimeException ignored) {
                // 관측 실패가 녹음의 저장·응답 결과를 바꾸지 않게 한다.
            }
        }
    }
}
