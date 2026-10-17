package com.knot.backend.workspace.application;

import com.knot.backend.global.exception.ErrorCategory;
import com.knot.backend.global.exception.ErrorCode;
import com.knot.backend.global.exception.ProjectException;
import com.knot.backend.global.logging.SafeExceptionDiagnostics;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.slf4j.spi.LoggingEventBuilder;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;

public final class WorkspaceLog {
    private static final Logger LOG = LoggerFactory.getLogger(WorkspaceLog.class);
    private static final JsonMapper JSON = JsonMapper.builder()
            .build();

    private WorkspaceLog() {}

    public static Scope begin(
            String event,
            Long workspaceId
    ) {
        return new Scope(
                event,
                workspaceId,
                null,
                null
        );
    }

    public static Scope discard(
            long workspaceId,
            Long recordingId,
            String reason
    ) {
        return new Scope(
                "recording.discard",
                workspaceId,
                recordingId,
                reason
        ).outcome("discarded");
    }

    public static final class Scope {
        private final String event;
        private final Long recordingId;
        private final String reason;
        private final String requestId = MDC.get("requestId");
        private final long startedAt = System.nanoTime();
        private final boolean transactional;
        private Long workspaceId;
        private String outcome = "completed";
        private String errorCode;
        private String failedStage;
        private Throwable failure;
        private boolean finished;

        private Scope(
                String event,
                Long workspaceId,
                Long recordingId,
                String reason
        ) {
            this.event = event;
            this.workspaceId = workspaceId;
            this.recordingId = recordingId;
            this.reason = reason;
            transactional = TransactionSynchronizationManager.isActualTransactionActive()
                    && TransactionSynchronizationManager.isSynchronizationActive();
            emit(
                    "operation",
                    "started",
                    startedAt
            );
            if (transactional) {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void afterCompletion(int status) {
                        complete(status);
                    }
                });
            }
        }

        public Scope workspace(long workspaceId) {
            this.workspaceId = workspaceId;
            return this;
        }

        public Scope outcome(String outcome) {
            this.outcome = outcome;
            return this;
        }

        public <T> T call(Supplier<T> action) {
            try {
                return action.get();
            } catch (RuntimeException exception) {
                recordFailure(
                        "operation",
                        exception
                );
                throw exception;
            } finally {
                if (!transactional) {
                    complete(TransactionSynchronization.STATUS_COMMITTED);
                }
            }
        }

        public void run(Runnable action) {
            call(() -> {
                action.run();
                return null;
            });
        }

        public <T> T step(
                String stage,
                Supplier<T> action
        ) {
            long stepStartedAt = System.nanoTime();
            emit(
                    stage,
                    "started",
                    stepStartedAt
            );
            try {
                T result = action.get();
                emit(
                        stage,
                        "prepared",
                        stepStartedAt
                );
                return result;
            } catch (RuntimeException exception) {
                recordFailure(
                        stage,
                        exception
                );
                throw exception;
            }
        }

        public void runStep(
                String stage,
                Runnable action
        ) {
            step(
                    stage,
                    () -> {
                        action.run();
                        return null;
                    }
            );
        }

        private void recordFailure(
                String stage,
                RuntimeException exception
        ) {
            if (failure != null) {
                return;
            }
            failure = exception;
            failedStage = stage;
            outcome = "failed";
            errorCode = "INTERNAL_SERVER_ERROR";
            if (!(exception instanceof ProjectException projectException)) {
                return;
            }
            recordProjectFailure(projectException);
        }

        private void recordProjectFailure(ProjectException exception) {
            ErrorCode code = exception.getErrorCode();
            errorCode = safeErrorCode(code);
            if (exception.getCause() != null || code.getCategory() == ErrorCategory.INTERNAL_SERVER_ERROR) {
                return;
            }
            outcome = "rejected";
        }

        private String safeErrorCode(ErrorCode code) {
            if (code instanceof Enum<?> typedCode) {
                return typedCode.name();
            }
            return "UNKNOWN_FAILURE";
        }

        private void complete(int status) {
            if (finished) {
                return;
            }
            finished = true;
            resolveTransactionOutcome(status);
            emit(
                    "operation",
                    outcome,
                    startedAt
            );
        }

        private void resolveTransactionOutcome(int status) {
            if (status == TransactionSynchronization.STATUS_UNKNOWN) {
                markUnconfirmed();
                return;
            }
            if (status != TransactionSynchronization.STATUS_ROLLED_BACK || failure != null) {
                return;
            }
            markRolledBack();
        }

        private void markUnconfirmed() {
            outcome = "unconfirmed";
            errorCode = "TRANSACTION_UNCONFIRMED";
            if (failure != null) {
                return;
            }
            failedStage = "transaction_completion";
        }

        private void markRolledBack() {
            outcome = "failed";
            errorCode = "TRANSACTION_ROLLED_BACK";
            failedStage = "transaction_completion";
            if (reason == null) {
                return;
            }
            outcome = "rolled_back";
        }

        private void emit(
                String stage,
                String emittedOutcome,
                long from
        ) {
            try {
                Map<String, Object> fields = new LinkedHashMap<>();
                fields.put(
                        "event",
                        event
                );
                fields.put(
                        "outcome",
                        emittedOutcome
                );
                fields.put(
                        "stage",
                        stage
                );
                fields.put(
                        "requestId",
                        requestId
                );
                fields.put(
                        "workspaceId",
                        workspaceId
                );
                if (reason != null) {
                    fields.put(
                            "recordingId",
                            recordingId
                    );
                    fields.put(
                            "reason",
                            reason
                    );
                }
                if (!"started".equals(emittedOutcome)) {
                    fields.put(
                            "elapsedMs",
                            (System.nanoTime() - from) / 1_000_000
                    );
                }
                addOperationFields(
                        fields,
                        stage,
                        emittedOutcome
                );
                LoggingEventBuilder builder = switch (emittedOutcome) {
                    case "failed" -> LOG.atError();
                    case "unconfirmed" -> LOG.atWarn();
                    default -> LOG.atInfo();
                };
                fields.forEach(builder::addKeyValue);
                builder.log(JSON.writeValueAsString(fields));
            } catch (RuntimeException ignored) {
                // 관측 실패가 업무 결과나 트랜잭션 결과를 바꾸지 않도록 한다.
            }
        }

        private void addOperationFields(
                Map<String, Object> fields,
                String stage,
                String emittedOutcome
        ) {
            if (!"operation".equals(stage) || "started".equals(emittedOutcome)) {
                return;
            }
            if (errorCode != null) {
                fields.put(
                        "errorCode",
                        errorCode
                );
                fields.put(
                        "failedStage",
                        failedStage
                );
            }
            if (failure == null || "rejected".equals(emittedOutcome)) {
                return;
            }
            fields.put(
                    "diagnostic",
                    SafeExceptionDiagnostics.describe(failure)
            );
        }
    }
}
