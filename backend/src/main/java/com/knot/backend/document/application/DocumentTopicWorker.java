package com.knot.backend.document.application;

import com.knot.backend.document.application.dto.result.DocumentTopicExecution;
import com.knot.backend.document.domain.DocumentException;
import com.knot.backend.document.domain.DocumentErrorCode;
import com.knot.backend.document.domain.model.DocumentTopicPlan;
import com.knot.backend.document.infrastructure.llm.LlmDocumentTopicPlanner;
import com.knot.backend.global.exception.ErrorCode;
import com.knot.backend.global.exception.LlmErrorCode;
import com.knot.backend.global.exception.LlmException;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;

@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "knot.document-topic-reorganization", name = "enabled", havingValue = "true")
public class DocumentTopicWorker {
    private static final Set<ErrorCode> TRANSIENT = Set.of(
            LlmErrorCode.LLM_TIMEOUT,
            LlmErrorCode.LLM_RATE_LIMITED,
            LlmErrorCode.LLM_UNAVAILABLE
    );

    private final DocumentTopicCheckpointService checkpoints;
    private final DocumentTopicApplyService applyService;
    private final LlmDocumentTopicPlanner planner;
    private final MeterRegistry metrics;

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void execute(
            long workspaceId,
            long operationId
    ) {
        Optional<DocumentTopicExecution> claimed = checkpoints.claim(
                workspaceId,
                operationId
        );
        if (claimed.isEmpty()) {
            return;
        }
        DocumentTopicExecution execution = claimed
                .orElseThrow(() -> new DocumentException(DocumentErrorCode.TOPIC_OPERATION_CONFLICT));
        long started = System.nanoTime();
        try {
            if (!executeClaimed(execution)) {
                metrics.counter(
                        "knot.document.topic.steps",
                        "result",
                        "discarded"
                )
                        .increment();
                return;
            }
            metrics.counter(
                    "knot.document.topic.steps",
                    "result",
                    "success"
            )
                    .increment();
        } catch (LlmException exception) {
            fail(
                    execution,
                    exception.getErrorCode()
                            .getCode(),
                    TRANSIENT.contains(exception.getErrorCode())
            );
        } catch (DocumentException exception) {
            fail(
                    execution,
                    exception.getErrorCode()
                            .getCode(),
                    false
            );
        } catch (DataAccessException exception) {
            fail(
                    execution,
                    "STORAGE_ERROR",
                    true
            );
        } catch (RuntimeException exception) {
            fail(
                    execution,
                    "INTERNAL_ERROR",
                    false
            );
        } finally {
            metrics.timer("knot.document.topic.step.duration")
                    .record(
                            System.nanoTime() - started,
                            TimeUnit.NANOSECONDS
                    );
        }
    }

    private boolean executeClaimed(DocumentTopicExecution execution) {
        if (!execution.consolidate() && execution.targets()
                .isEmpty()) {
            return apply(execution);
        }
        if (execution.consolidate() && execution.categories()
                .isEmpty()) {
            return checkpoints.complete(
                    execution,
                    new DocumentTopicPlan(
                            Map.of(),
                            Map.of(),
                            Map.of(),
                            Map.of()
                    )
            );
        }
        return planAndCheckpoint(execution);
    }

    private boolean apply(DocumentTopicExecution execution) {
        if (!checkpoints.beginApply(execution)) {
            return false;
        }
        applyService.apply(execution);
        return true;
    }

    private boolean planAndCheckpoint(DocumentTopicExecution execution) {
        try {
            return completePlan(execution);
        } catch (LlmException exception) {
            if (!checkpoints.isClaimCurrent(execution)) {
                return false;
            }
            if (!canSplit(
                    execution,
                    exception
            )) {
                throw exception;
            }
            return planAndCheckpoint(execution.firstHalf());
        }
    }

    private boolean canSplit(
            DocumentTopicExecution execution,
            LlmException exception
    ) {
        return !execution.consolidate() && execution.targets()
                .size() > 1 && exception.getErrorCode() == LlmErrorCode.LLM_INPUT_LIMIT_EXCEEDED;
    }

    private boolean completePlan(DocumentTopicExecution execution) {
        DocumentTopicPlan plan = planner.plan(execution);
        return checkpoints.complete(
                execution,
                plan
        );
    }

    private void fail(
            DocumentTopicExecution execution,
            String code,
            boolean retryable
    ) {
        checkpoints.fail(
                execution,
                code,
                retryable
        );
        metrics.counter(
                "knot.document.topic.steps",
                "result",
                code
        )
                .increment();
        log.warn(
                "Document topic step failed workspaceId={} operationId={} code={} retryable={}",
                execution.workspaceId(),
                execution.operationId(),
                code,
                retryable
        );
    }
}
