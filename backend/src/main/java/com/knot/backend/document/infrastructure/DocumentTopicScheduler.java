package com.knot.backend.document.infrastructure;

import com.knot.backend.document.application.DocumentTopicWorker;
import com.knot.backend.document.application.DocumentTopicRestorationWorker;
import com.knot.backend.document.domain.model.DocumentTopicOperationKind;
import com.knot.backend.document.application.DocumentTopicCancellationService;
import com.knot.backend.document.domain.model.DocumentTopicOperation;
import com.knot.backend.document.domain.repository.DocumentTopicOperationRepository;
import java.time.Clock;
import java.util.List;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.stereotype.Component;
import org.springframework.dao.DataAccessException;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@EnableScheduling
@ConditionalOnProperty(prefix = "knot.document-topic-reorganization", name = "enabled", havingValue = "true")
public class DocumentTopicScheduler {

    private final DocumentTopicOperationRepository operations;
    private final DocumentTopicWorker worker;
    private final DocumentTopicRestorationWorker restorations;
    private final TaskExecutor executor;
    private final Clock clock;
    private final DocumentTopicCancellationService cancellations;

    public DocumentTopicScheduler(
            DocumentTopicOperationRepository operations,
            DocumentTopicWorker worker,
            DocumentTopicRestorationWorker restorations,
            @Qualifier("documentTopicExecutor") TaskExecutor executor,
            Clock clock,
            DocumentTopicCancellationService cancellations
    ) {
        this.operations = operations;
        this.worker = worker;
        this.restorations = restorations;
        this.executor = executor;
        this.clock = clock;
        this.cancellations = cancellations;
    }

    @Scheduled(fixedDelayString = "${knot.document-topic-reorganization.poll-delay:1000}")
    public void dispatch() {
        completeCancellations();
        List<DocumentTopicOperation> ready = operations.findDispatchable(clock.instant());
        for (DocumentTopicOperation operation : ready) {
            if (!submit(operation)) {
                return;
            }
        }
    }

    private void completeCancellations() {
        List<DocumentTopicOperation> pending = operations.findCancellationRequested();
        for (DocumentTopicOperation operation : pending) {
            completeCancellation(operation);
        }
    }

    private void completeCancellation(DocumentTopicOperation operation) {
        try {
            cancellations.complete(
                    operation.getWorkspaceId(),
                    operation.getId()
            );
        } catch (DataAccessException exception) {
            log.warn(
                    "Document topic cancellation cleanup failed workspaceId={} operationId={}",
                    operation.getWorkspaceId(),
                    operation.getId(),
                    exception
            );
        }
    }

    private void execute(DocumentTopicOperation operation) {
        if (operation.getKind() == DocumentTopicOperationKind.RESTORE) {
            restorations.execute(
                    operation.getWorkspaceId(),
                    operation.getId()
            );
            return;
        }
        worker.execute(
                operation.getWorkspaceId(),
                operation.getId()
        );
    }

    private boolean submit(DocumentTopicOperation operation) {
        try {
            executor.execute(() -> execute(operation));
            return true;
        } catch (TaskRejectedException exception) {
            return false;
        }
    }
}
