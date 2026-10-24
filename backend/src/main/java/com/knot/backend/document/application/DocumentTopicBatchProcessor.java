package com.knot.backend.document.application;

import com.knot.backend.document.application.dto.result.DocumentTopicExecution;
import com.knot.backend.document.domain.DocumentException;
import com.knot.backend.document.domain.model.DocumentTopicPlan;
import com.knot.backend.document.infrastructure.llm.LlmDocumentTopicPlanner;
import com.knot.backend.global.exception.ErrorCode;
import com.knot.backend.global.exception.LlmErrorCode;
import com.knot.backend.global.exception.LlmException;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "knot.document-topic-reorganization", name = "enabled", havingValue = "true")
public class DocumentTopicBatchProcessor {
    private static final Set<ErrorCode> TRANSIENT = Set.of(
            LlmErrorCode.LLM_TIMEOUT,
            LlmErrorCode.LLM_RATE_LIMITED,
            LlmErrorCode.LLM_UNAVAILABLE
    );

    private final DocumentTopicCheckpointService checkpoints;
    private final DocumentTopicBatchCheckpointService batchCheckpoints;
    private final LlmDocumentTopicPlanner planner;
    private final MeterRegistry metrics;
    private final AtomicInteger activeRequests = new AtomicInteger();
    private final AtomicInteger peakRequests = new AtomicInteger();

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public DocumentTopicExecution process(DocumentTopicExecution execution) {
        long started = System.nanoTime();
        try {
            plan(execution);
        } catch (LlmException exception) {
            handleModelFailure(
                    execution,
                    exception
            );
        } catch (DocumentException exception) {
            checkpoints.fail(
                    execution,
                    exception.getErrorCode()
                            .getCode(),
                    false
            );
        } catch (DataAccessException exception) {
            retry(
                    execution,
                    "STORAGE_ERROR"
            );
        } catch (RuntimeException exception) {
            checkpoints.fail(
                    execution,
                    "INTERNAL_ERROR",
                    false
            );
        } finally {
            metrics.timer("knot.document.topic.batch.duration")
                    .record(
                            System.nanoTime() - started,
                            TimeUnit.NANOSECONDS
                    );
        }
        return execution;
    }

    private void plan(DocumentTopicExecution execution) {
        if (!batchCheckpoints.renew(execution)) {
            return;
        }
        DocumentTopicPlan plan = request(execution);
        batchCheckpoints.completeBatch(
                execution,
                plan
        );
    }

    private DocumentTopicPlan request(DocumentTopicExecution execution) {
        int active = activeRequests.incrementAndGet();
        peakRequests.accumulateAndGet(
                active,
                Math::max
        );
        metrics.gauge(
                "knot.document.topic.batch.requests.active",
                activeRequests
        );
        metrics.gauge(
                "knot.document.topic.batch.requests.peak",
                peakRequests
        );
        try {
            return planner.plan(execution);
        } finally {
            activeRequests.decrementAndGet();
        }
    }

    private void handleModelFailure(
            DocumentTopicExecution execution,
            LlmException exception
    ) {
        if (exception.getErrorCode() == LlmErrorCode.LLM_CALL_INTERRUPTED) {
            throw exception;
        }
        if (canSplit(
                execution,
                exception
        )) {
            process(execution.firstHalf());
            process(execution.secondHalf());
            return;
        }
        String code = exception.getErrorCode()
                .getCode();
        if (TRANSIENT.contains(exception.getErrorCode())) {
            retry(
                    execution,
                    code
            );
            return;
        }
        checkpoints.fail(
                execution,
                code,
                false
        );
    }

    private boolean canSplit(
            DocumentTopicExecution execution,
            LlmException exception
    ) {
        return execution.targets()
                .size() > 1 && isSizeLimit(exception.getErrorCode());
    }

    private boolean isSizeLimit(ErrorCode code) {
        return code == LlmErrorCode.LLM_INPUT_LIMIT_EXCEEDED || code == LlmErrorCode.LLM_OUTPUT_LIMIT_EXCEEDED;
    }

    private void retry(
            DocumentTopicExecution execution,
            String code
    ) {
        batchCheckpoints.retryBatch(
                execution,
                code
        );
        log.warn(
                "Document topic batch retry workspaceId={} operationId={} code={}",
                execution.workspaceId(),
                execution.operationId(),
                code
        );
    }
}
