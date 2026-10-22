package com.knot.backend.document.infrastructure;

import com.knot.backend.document.application.DocumentCategoryPublished;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Slf4j
@Component
@RequiredArgsConstructor
public class DocumentCategoryPublicationMetrics {

    private final MeterRegistry registry;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onPublication(DocumentCategoryPublished event) {
        try {
            record(event);
        } catch (RuntimeException exception) {
            log.warn(
                    "Document category metric failed workspaceId={} jobId={} documentId={}",
                    event.workspaceId(),
                    event.jobId(),
                    event.documentId(),
                    exception
            );
        }
    }

    private void record(DocumentCategoryPublished event) {
        String source = event.source()
                .name();
        registry.counter(
                "knot.document.category.publications",
                "source",
                source
        )
                .increment();
        Duration elapsed = event.elapsed();
        registry.timer(
                "knot.document.category.assignment.duration",
                "source",
                source
        )
                .record(elapsed);
        log.info(
                "Document category published workspaceId={} jobId={} documentId={} source={} ruleVersion={} reason={} attempts={} elapsedMs={}",
                event.workspaceId(),
                event.jobId(),
                event.documentId(),
                source,
                event.ruleVersion(),
                event.reason(),
                event.attempts(),
                elapsed.toMillis()
        );
    }
}
