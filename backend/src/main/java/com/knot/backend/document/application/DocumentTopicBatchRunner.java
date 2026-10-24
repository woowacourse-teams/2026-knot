package com.knot.backend.document.application;

import com.knot.backend.document.application.dto.result.DocumentTopicExecution;
import com.knot.backend.document.application.dto.result.DocumentTopicTarget;
import com.knot.backend.document.domain.DocumentErrorCode;
import com.knot.backend.document.domain.DocumentException;
import com.knot.backend.global.config.DocumentTopicReorganizationProperties;
import com.knot.backend.global.exception.LlmErrorCode;
import com.knot.backend.global.exception.LlmException;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(prefix = "knot.document-topic-reorganization", name = "enabled", havingValue = "true")
public class DocumentTopicBatchRunner {

    private final DocumentTopicCheckpointService checkpoints;
    private final DocumentTopicBatchCheckpointService batchCheckpoints;
    private final DocumentTopicBatchProcessor processor;
    private final DocumentTopicReorganizationProperties properties;
    private final Executor executor;

    public DocumentTopicBatchRunner(
            DocumentTopicCheckpointService checkpoints,
            DocumentTopicBatchCheckpointService batchCheckpoints,
            DocumentTopicBatchProcessor processor,
            DocumentTopicReorganizationProperties properties,
            @Qualifier("documentTopicPlanningExecutor") Executor executor
    ) {
        this.checkpoints = checkpoints;
        this.batchCheckpoints = batchCheckpoints;
        this.processor = processor;
        this.properties = properties;
        this.executor = executor;
    }

    public boolean execute(DocumentTopicExecution execution) {
        CompletionService<DocumentTopicExecution> completions = new ExecutorCompletionService<>(executor);
        Map<Future<DocumentTopicExecution>, Set<Long>> active = new HashMap<>();
        try {
            return run(
                    execution,
                    completions,
                    active
            );
        } finally {
            active.keySet()
                    .forEach(future -> future.cancel(true));
        }
    }

    private boolean run(
            DocumentTopicExecution execution,
            CompletionService<DocumentTopicExecution> completions,
            Map<Future<DocumentTopicExecution>, Set<Long>> active
    ) {
        while (checkpoints.isClaimCurrent(execution)) {
            fill(
                    execution,
                    completions,
                    active
            );
            if (active.isEmpty()) {
                return batchCheckpoints.finishBatches(execution);
            }
            collect(
                    completions,
                    active
            );
        }
        return false;
    }

    private void fill(
            DocumentTopicExecution execution,
            CompletionService<DocumentTopicExecution> completions,
            Map<Future<DocumentTopicExecution>, Set<Long>> active
    ) {
        while (active.size() < properties.getParallelism()) {
            Set<Long> excluded = active.values()
                    .stream()
                    .flatMap(Set::stream)
                    .collect(Collectors.toSet());
            Optional<DocumentTopicExecution> pending = batchCheckpoints.nextBatch(
                    execution,
                    excluded
            );
            if (pending.isEmpty()) {
                return;
            }
            DocumentTopicExecution batch = pending
                    .orElseThrow(() -> new DocumentException(DocumentErrorCode.TOPIC_OPERATION_CONFLICT));
            Future<DocumentTopicExecution> submitted = completions.submit(() -> processor.process(batch));
            Set<Long> ids = batch.targets()
                    .stream()
                    .map(DocumentTopicTarget::documentId)
                    .collect(Collectors.toSet());
            active.put(
                    submitted,
                    ids
            );
        }
    }

    private void collect(
            CompletionService<DocumentTopicExecution> completions,
            Map<Future<DocumentTopicExecution>, Set<Long>> active
    ) {
        try {
            Future<DocumentTopicExecution> completed = completions.poll(
                    1,
                    TimeUnit.SECONDS
            );
            if (completed == null) {
                return;
            }
            completed.get();
            active.remove(completed);
        } catch (InterruptedException exception) {
            Thread.currentThread()
                    .interrupt();
            throw new LlmException(
                    LlmErrorCode.LLM_CALL_INTERRUPTED,
                    exception
            );
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            if (RuntimeException.class.isInstance(cause)) {
                throw RuntimeException.class.cast(cause);
            }
            throw new DocumentException(DocumentErrorCode.TOPIC_OPERATION_CONFLICT);
        }
    }
}
