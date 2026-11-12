package com.knot.backend.global.logging;

import com.knot.backend.global.exception.ErrorCategory;
import com.knot.backend.global.exception.ProjectException;
import com.knot.backend.search.domain.SearchToolException;
import java.time.DateTimeException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.ArrayList;
import java.util.List;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.DisconnectedClientHelper;
import java.util.concurrent.CancellationException;
import org.slf4j.Logger;
import org.springframework.security.oauth2.jwt.BadJwtException;
import tools.jackson.core.JacksonException;

public final class ServerErrorLogger {
    private static final int MAX_CAUSES = 16;
    private static final Set<String> CONTROL_CODES = Set.of(
            "LLM_CALL_INTERRUPTED",
            "SEARCH_TRANSPORT_INTERRUPTED",
            "SEARCH_ACCESS_DENIED"
    );
    private static final Set<String> RECOVERABLE_CODES = Set.of(
            "LLM_TIMEOUT",
            "LLM_RATE_LIMITED",
            "LLM_UNAVAILABLE",
            "LLM_INVALID_RESPONSE",
            "INVALID_TOPIC_CLASSIFICATION_RESPONSE",
            "INVALID_DOCUMENT_GENERATION_RESPONSE",
            "SEARCH_TRANSPORT_TIMEOUT",
            "SEARCH_RATE_LIMITED",
            "SEARCH_UPSTREAM_UNAVAILABLE",
            "SEARCH_REQUEST_REJECTED",
            "SEARCH_TRANSPORT_FAILED",
            "INVALID_MODEL_ANSWER",
            "INCOMPLETE_MODEL_STREAM",
            "TRANSPORT_RESPONSE_TOO_LARGE",
            "LLM_REQUEST_REJECTED",
            "LLM_INPUT_LIMIT_EXCEEDED",
            "LLM_OUTPUT_LIMIT_EXCEEDED",
            "AUDIO_STORAGE_UNAVAILABLE",
            "INCOMPLETE_DOCUMENT_EXPLORATION",
            "INVALID_MCP_RESPONSE",
            "INVALID_MCP_TOOLS",
            "INVALID_MODEL_RESPONSE",
            "INVALID_MODEL_TOOL_CALL",
            "MCP_TOOL_FAILED",
            "MODEL_GENERATION_FAILED",
            "REPEATED_TOOL_CALL",
            "SEARCH_CONTEXT_LIMIT_EXCEEDED",
            "SEARCH_TOOL_LIMIT_EXCEEDED",
            "UNEXPECTED_MODEL_TOOL_CALL",
            "UNSUPPORTED_MCP_PROTOCOL"
    );
    private static final Set<String> INPUT_CODES = Set.of(
            "INVALID_TOOL_ARGUMENTS",
            "UNKNOWN_TOOL",
            "DOCUMENT_NOT_FOUND",
            "DOCUMENT_CHANGED"
    );
    private static final Set<String> UNIQUE_CONFLICT_CODES = Set.of(
            "ACTIVE_RECORDING_ALREADY_EXISTS",
            "RECORDING_START_REQUEST_CONFLICT",
            "NICKNAME_SETUP_ALREADY_COMPLETED"
    );
    private static final Set<Class<? extends Throwable>> EXPECTED_CAUSE_TYPES = Set.of(
            BadJwtException.class,
            IllegalArgumentException.class,
            DateTimeException.class,
            JacksonException.class,
            ClassCastException.class,
            AuthenticationException.class,
            RestClientException.class,
            software.amazon.awssdk.core.exception.SdkException.class
    );

    private ServerErrorLogger() {}

    public static void record(
            Logger logger,
            String context,
            Throwable exception
    ) {
        if (isControlFlow(exception)) {
            logger.debug(
                    "{} diagnostic={}",
                    context,
                    SafeExceptionDiagnostics.describe(exception)
            );
            return;
        }
        if (isRecoverable(exception)) {
            warn(
                    logger,
                    context,
                    exception
            );
            return;
        }
        if (isExpectedProjectFailure(exception)) {
            return;
        }
        if (isExpectedToolFailure(exception)) {
            return;
        }
        error(
                logger,
                context,
                exception
        );
    }

    public static void error(
            Logger logger,
            String context,
            Throwable exception
    ) {
        logger.error(
                "{} diagnostic={}",
                context,
                SafeExceptionDiagnostics.describe(exception)
        );
    }

    public static void recordTaskFailure(
            Logger logger,
            String context,
            Throwable exception
    ) {
        if (isControlFlow(exception)) {
            debug(
                    logger,
                    context,
                    exception
            );
            return;
        }
        if (isExpectedProjectFailure(exception) || isExpectedToolFailure(exception)) {
            warn(
                    logger,
                    context,
                    exception
            );
            return;
        }
        record(
                logger,
                context,
                exception
        );
    }

    public static void warn(
            Logger logger,
            String context,
            Throwable exception
    ) {
        logger.warn(
                "{} diagnostic={}",
                context,
                SafeExceptionDiagnostics.describe(exception)
        );
    }

    public static void debug(
            Logger logger,
            String context,
            Throwable exception
    ) {
        logger.debug(
                "{} diagnostic={}",
                context,
                SafeExceptionDiagnostics.describe(exception)
        );
    }

    public static String safeFailureCode(Throwable exception) {
        String failureCode = code(exception);
        if (failureCode.isBlank()) {
            return "UNCLASSIFIED";
        }
        return failureCode;
    }

    public static boolean isInternalFailure(Throwable exception) {
        if (isKnownConflict(exception)) {
            return false;
        }
        return containsUnexpectedCause(exception);
    }

    public static boolean isControlFlow(Throwable exception) {
        if (exception == null) {
            return false;
        }
        if (isClientWriteFailure(exception)) {
            return true;
        }
        if ("ANSWER_GENERATION_UNAVAILABLE".equals(code(exception)) && exception.getCause() == null) {
            return true;
        }
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        Throwable current = exception;
        while (current != null && visited.size() < MAX_CAUSES && visited.add(current)) {
            if (isControlNode(current)) {
                return true;
            }
            if (!isControlWrapper(current)) {
                return false;
            }
            current = current.getCause();
        }
        return false;
    }

    private static boolean isRecoverable(Throwable exception) {
        if (!RECOVERABLE_CODES.contains(code(exception))) {
            return false;
        }
        return !containsUnexpectedCause(exception);
    }

    private static boolean isClientWriteFailure(Throwable exception) {
        if (java.io.IOException.class.isInstance(exception)) {
            return DisconnectedClientHelper.isClientDisconnectedException(exception);
        }
        if (org.springframework.http.converter.HttpMessageNotWritableException.class.isInstance(exception)) {
            return DisconnectedClientHelper.isClientDisconnectedException(exception);
        }
        return false;
    }

    private static boolean isControlNode(Throwable exception) {
        if (CancellationException.class.isInstance(exception) || InterruptedException.class.isInstance(exception)
                || java.util.concurrent.RejectedExecutionException.class.isInstance(exception)) {
            return true;
        }
        return CONTROL_CODES.contains(code(exception)) && !containsUnexpectedCause(exception);
    }

    private static boolean isControlWrapper(Throwable exception) {
        return ProjectException.class.isInstance(exception) || SearchToolException.class.isInstance(exception)
                || java.util.concurrent.ExecutionException.class.isInstance(exception);
    }

    private static boolean containsUnexpectedCause(Throwable exception) {
        if (exception == null) {
            return false;
        }
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        List<Throwable> pending = new ArrayList<>();
        pending.add(exception);
        for (int index = 0; index < pending.size(); index++) {
            Throwable current = pending.get(index);
            if (!visited.add(current)) {
                continue;
            }
            if (hasUnexpectedCause(current)) {
                return true;
            }
            if (isUnexpectedProjectFailure(current)) {
                return true;
            }
            if (isUnexpectedToolFailure(current)) {
                return true;
            }
            appendRelated(
                    pending,
                    current
            );
        }
        return false;
    }

    private static boolean isUnexpectedProjectFailure(Throwable exception) {
        if (!ProjectException.class.isInstance(exception)) {
            return false;
        }
        if (RECOVERABLE_CODES.contains(code(exception)) || CONTROL_CODES.contains(code(exception))) {
            return false;
        }
        return ProjectException.class.cast(exception)
                .getErrorCode()
                .getCategory() == ErrorCategory.INTERNAL_SERVER_ERROR;
    }

    private static boolean isUnexpectedToolFailure(Throwable exception) {
        if (!SearchToolException.class.isInstance(exception)) {
            return false;
        }
        if (INPUT_CODES.contains(code(exception)) || CONTROL_CODES.contains(code(exception))) {
            return false;
        }
        return !RECOVERABLE_CODES.contains(code(exception));
    }

    private static void appendRelated(
            List<Throwable> pending,
            Throwable exception
    ) {
        for (Throwable suppressed : exception.getSuppressed()) {
            appendBounded(
                    pending,
                    suppressed
            );
        }
        if (BadJwtException.class.isInstance(exception) || RestClientException.class.isInstance(exception)) {
            return;
        }
        appendBounded(
                pending,
                exception.getCause()
        );
    }

    private static void appendBounded(
            List<Throwable> pending,
            Throwable exception
    ) {
        if (exception == null || pending.size() >= MAX_CAUSES) {
            return;
        }
        pending.add(exception);
    }

    private static boolean isExpectedProjectFailure(Throwable exception) {
        return ProjectException.class.isInstance(exception) && !isInternalFailure(exception);
    }

    private static boolean isExpectedToolFailure(Throwable exception) {
        return INPUT_CODES.contains(code(exception)) && !isInternalFailure(exception);
    }

    private static boolean isKnownConflict(Throwable exception) {
        if (!ProjectException.class.isInstance(exception)) {
            return false;
        }
        return UNIQUE_CONFLICT_CODES.contains(code(exception));
    }

    private static boolean hasUnexpectedCause(Throwable exception) {
        if (java.sql.SQLException.class.isInstance(exception)) {
            return true;
        }
        if (exception == null || ProjectException.class.isInstance(exception)
                || SearchToolException.class.isInstance(exception)) {
            return false;
        }
        if (isExpectedCauseType(exception)) {
            return false;
        }
        return RuntimeException.class.isInstance(exception) && !isControlFlow(exception);
    }

    private static boolean isExpectedCauseType(Throwable exception) {
        return EXPECTED_CAUSE_TYPES.stream()
                .anyMatch(type -> type.isInstance(exception));
    }

    private static String code(Throwable exception) {
        if (ProjectException.class.isInstance(exception)) {
            return ProjectException.class.cast(exception)
                    .getErrorCode()
                    .getCode();
        }
        if (SearchToolException.class.isInstance(exception)) {
            return SearchToolException.class.cast(exception)
                    .getCode();
        }
        return "";
    }
}
