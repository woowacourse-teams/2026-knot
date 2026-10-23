package com.knot.backend.document.infrastructure;

import com.knot.backend.document.application.DocumentTopicWorker;
import com.knot.backend.document.domain.model.DocumentTopicOperation;
import com.knot.backend.document.domain.repository.DocumentTopicOperationRepository;
import java.time.Clock;
import java.util.List;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "knot.document-topic-reorganization", name = "enabled", havingValue = "true")
public class DocumentTopicScheduler {

    private final DocumentTopicOperationRepository operations;
    private final DocumentTopicWorker worker;
    private final TaskExecutor executor;
    private final Clock clock;

    public DocumentTopicScheduler(
            DocumentTopicOperationRepository operations,
            DocumentTopicWorker worker,
            @Qualifier("documentTopicExecutor") TaskExecutor executor,
            Clock clock
    ) {
        this.operations = operations;
        this.worker = worker;
        this.executor = executor;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${knot.document-topic-reorganization.poll-delay:1000}")
    public void dispatch() {
        List<DocumentTopicOperation> ready = operations.findDispatchable(clock.instant());
        for (DocumentTopicOperation operation : ready) {
            if (!submit(operation)) {
                return;
            }
        }
    }

    private boolean submit(DocumentTopicOperation operation) {
        try {
            executor.execute(
                    () -> worker.execute(
                            operation.getWorkspaceId(),
                            operation.getId()
                    )
            );
            return true;
        } catch (TaskRejectedException exception) {
            return false;
        }
    }
}
