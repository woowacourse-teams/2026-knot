package com.knot.backend.document.application;

import com.knot.backend.global.logging.ServerErrorLogger;
import com.knot.backend.document.application.dto.result.DocumentGenerationExecution;
import com.knot.backend.document.domain.DocumentErrorCode;
import com.knot.backend.document.domain.DocumentException;
import com.knot.backend.document.domain.model.DocumentGenerationFailureCause;
import com.knot.backend.document.domain.model.DocumentGenerationJobStage;
import com.knot.backend.document.domain.model.DocumentGenerationPhase;
import com.knot.backend.document.application.dto.result.DocumentGenerationResult;
import com.knot.backend.global.config.DocumentCategoryProperties;
import com.knot.backend.global.exception.LlmErrorCode;
import com.knot.backend.global.exception.ErrorCode;
import com.knot.backend.global.exception.LlmException;
import java.util.Optional;
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
@ConditionalOnProperty(prefix = "knot.llm", name = "enabled", havingValue = "true")
public class DocumentGenerationWorker {

    private final DocumentGenerationClaimService claims;
    private final DocumentTopicClassificationService classifier;
    private final DocumentGenerationService generator;
    private final DocumentClassificationResultService classificationResults;
    private final DocumentGenerationResultService generationResults;
    private final DocumentGenerationFailureService failures;
    private final DocumentCategoryProperties categoryProperties;
    private final DocumentCategoryCheckpointService checkpoints;
    private final DocumentCategoryAssignmentService categoryAssignments;

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void execute(
            long workspaceId,
            long jobId
    ) {
        Optional<DocumentGenerationExecution> claimed = claims.claim(
                workspaceId,
                jobId
        );
        claimed.ifPresent(this::executeClaimed);
    }

    private void executeClaimed(DocumentGenerationExecution execution) {
        try {
            executeStage(execution);
        } catch (LlmException exception) {
            fail(
                    execution,
                    llmCause(exception.getErrorCode()),
                    exception
            );
        } catch (DocumentException exception) {
            fail(
                    execution,
                    documentCause(exception),
                    exception
            );
        } catch (DataAccessException exception) {
            fail(
                    execution,
                    DocumentGenerationFailureCause.STORAGE,
                    exception
            );
        } catch (RuntimeException exception) {
            fail(
                    execution,
                    DocumentGenerationFailureCause.INTERNAL,
                    exception
            );
        }
    }

    private void executeStage(DocumentGenerationExecution execution) {
        if (execution.stage() == DocumentGenerationJobStage.CLASSIFICATION) {
            classificationResults.completeClassification(
                    execution.workspaceId(),
                    execution.jobId(),
                    execution.attemptCount(),
                    classifier.classify(execution.transcriptContent())
            );
            return;
        }
        executeGeneration(execution);
    }

    private void executeGeneration(DocumentGenerationExecution execution) {
        if (execution.processingPhase() == DocumentGenerationPhase.ASSIGN_CATEGORY) {
            categoryAssignments.assign(execution);
            return;
        }
        if (execution.processingPhase() == DocumentGenerationPhase.PUBLISH) {
            generationResults.completePreparedGeneration(
                    execution.workspaceId(),
                    execution.jobId(),
                    execution.attemptCount()
            );
            return;
        }
        DocumentGenerationResult result = generator.generate(
                execution.transcriptContent(),
                execution.topic()
        );
        if (categoryProperties.isEnabled()) {
            checkpoints.prepare(
                    execution,
                    result
            );
            return;
        }
        generationResults.completeGeneration(
                execution.workspaceId(),
                execution.jobId(),
                execution.attemptCount(),
                result
        );
    }

    private DocumentGenerationFailureCause llmCause(ErrorCode errorCode) {
        if (errorCode == LlmErrorCode.LLM_TIMEOUT || errorCode == LlmErrorCode.LLM_CALL_INTERRUPTED) {
            return DocumentGenerationFailureCause.TIMEOUT;
        }
        if (errorCode == LlmErrorCode.LLM_RATE_LIMITED) {
            return DocumentGenerationFailureCause.RATE_LIMITED;
        }
        if (errorCode == LlmErrorCode.LLM_UNAVAILABLE) {
            return DocumentGenerationFailureCause.UNAVAILABLE;
        }
        if (errorCode == LlmErrorCode.LLM_AUTHENTICATION_FAILED
                || errorCode == LlmErrorCode.LLM_INVALID_CONFIGURATION) {
            return DocumentGenerationFailureCause.AUTHENTICATION;
        }
        if (errorCode == LlmErrorCode.LLM_INPUT_LIMIT_EXCEEDED) {
            return DocumentGenerationFailureCause.INPUT_LIMIT;
        }
        if (errorCode == LlmErrorCode.LLM_OUTPUT_LIMIT_EXCEEDED) {
            return DocumentGenerationFailureCause.OUTPUT_LIMIT;
        }
        if (errorCode == LlmErrorCode.LLM_INVALID_RESPONSE) {
            return DocumentGenerationFailureCause.INVALID_RESPONSE;
        }
        return DocumentGenerationFailureCause.INVALID_INPUT;
    }

    private DocumentGenerationFailureCause documentCause(DocumentException exception) {
        if (exception.getErrorCode() == DocumentErrorCode.INVALID_TOPIC_CLASSIFICATION_RESPONSE
                || exception.getErrorCode() == DocumentErrorCode.INVALID_DOCUMENT_GENERATION_RESPONSE) {
            return DocumentGenerationFailureCause.INVALID_RESPONSE;
        }
        return DocumentGenerationFailureCause.INVALID_INPUT;
    }

    private void fail(
            DocumentGenerationExecution execution,
            DocumentGenerationFailureCause cause,
            Throwable failure
    ) {
        ServerErrorLogger.recordTaskFailure(
                log,
                "Document generation failed workspaceId=" + execution.workspaceId() + " jobId=" + execution.jobId()
                        + " stage=" + execution.stage() + " attempt=" + execution.attemptCount() + " cause=" + cause,
                failure
        );
        try {
            failures.failAttempt(
                    execution.workspaceId(),
                    execution.jobId(),
                    execution.attemptCount(),
                    cause
            );
        } catch (RuntimeException exception) {
            ServerErrorLogger.record(
                    log,
                    "Document failure persistence deferred workspaceId=" + execution.workspaceId() + " jobId="
                            + execution.jobId() + " stage=" + execution.stage() + " attempt="
                            + execution.attemptCount(),
                    exception
            );
        }
    }
}
