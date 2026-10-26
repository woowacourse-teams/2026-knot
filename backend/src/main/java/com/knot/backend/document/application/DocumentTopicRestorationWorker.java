package com.knot.backend.document.application;

import com.knot.backend.document.application.dto.result.DocumentTopicRestorationExecution;
import com.knot.backend.document.domain.DocumentException;
import com.knot.backend.document.domain.DocumentErrorCode;
import java.util.Optional;
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
public class DocumentTopicRestorationWorker {

    private final DocumentTopicRestorationCheckpointService checkpoints;
    private final DocumentTopicRestorationApplyService apply;

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void execute(
            long workspaceId,
            long operationId
    ) {
        Optional<DocumentTopicRestorationExecution> claimed = checkpoints.claim(
                workspaceId,
                operationId
        );
        if (claimed.isEmpty()) {
            return;
        }
        DocumentTopicRestorationExecution execution = claimed
                .orElseThrow(() -> new DocumentException(DocumentErrorCode.INVALID_TOPIC_RESTORATION_DATA));
        try {
            executeClaimed(execution);
        } catch (DataAccessException exception) {
            fail(
                    execution,
                    "STORAGE_ERROR",
                    true
            );
        } catch (DocumentException exception) {
            fail(
                    execution,
                    exception.getErrorCode()
                            .getCode(),
                    false
            );
        } catch (RuntimeException exception) {
            fail(
                    execution,
                    "INTERNAL_ERROR",
                    false
            );
        }
    }

    private void executeClaimed(DocumentTopicRestorationExecution execution) {
        if (!execution.readyToApply()) {
            checkpoints.prepare(execution);
            return;
        }
        if (checkpoints.beginApply(execution)) {
            apply.apply(execution);
        }
    }

    private void fail(
            DocumentTopicRestorationExecution execution,
            String code,
            boolean retryable
    ) {
        log.warn(
                "Document topic restoration failed workspaceId={} operationId={} code={} retryable={}",
                execution.workspaceId(),
                execution.operationId(),
                code,
                retryable
        );
        checkpoints.fail(
                execution,
                code,
                retryable
        );
    }
}
