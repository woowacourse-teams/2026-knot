package com.knot.backend.document.application;

import com.knot.backend.document.application.dto.result.DocumentCategoryAttempt;
import com.knot.backend.document.application.dto.result.DocumentGenerationExecution;
import com.knot.backend.document.domain.DocumentErrorCode;
import com.knot.backend.document.domain.DocumentException;
import com.knot.backend.document.domain.model.DocumentCategoryDecision;
import com.knot.backend.global.exception.ErrorCode;
import com.knot.backend.global.exception.LlmErrorCode;
import com.knot.backend.global.exception.LlmException;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "knot.llm", name = "enabled", havingValue = "true")
public class DocumentCategoryAssignmentService {

    private final DocumentCategoryCheckpointService checkpoints;
    private final DocumentCategoryAssigner assigner;

    public void assign(DocumentGenerationExecution execution) {
        DocumentCategoryAttempt attempt = checkpoints.reserve(execution);
        if (!attempt.canCall()) {
            checkpoints.failAssignment(
                    execution,
                    "BUDGET_EXHAUSTED",
                    false
            );
            return;
        }
        DocumentCategoryDecision decision = request(
                execution,
                attempt
        );
        if (decision == null) {
            return;
        }
        saveDecision(
                execution,
                decision,
                attempt.catalogVersion()
        );
    }

    private DocumentCategoryDecision request(
            DocumentGenerationExecution execution,
            DocumentCategoryAttempt attempt
    ) {
        try {
            return requireDecision(
                    assigner.assign(
                            attempt.input(),
                            attempt.timeout()
                    )
            );
        } catch (LlmException exception) {
            ErrorCode code = exception.getErrorCode();
            checkpoints.failAssignment(
                    execution,
                    code.getCode(),
                    isRetryable(code)
            );
            return null;
        } catch (DocumentException exception) {
            checkpoints.failAssignment(
                    execution,
                    "INVALID_RESPONSE",
                    true
            );
            return null;
        } catch (RuntimeException exception) {
            checkpoints.failAssignment(
                    execution,
                    "ASSIGNMENT_INTERNAL",
                    false
            );
            return null;
        }
    }

    private void saveDecision(
            DocumentGenerationExecution execution,
            DocumentCategoryDecision decision,
            String version
    ) {
        try {
            checkpoints.decide(
                    execution,
                    decision,
                    version
            );
        } catch (DocumentException exception) {
            if (exception.getErrorCode() != DocumentErrorCode.INVALID_DOCUMENT_CATEGORY_RESPONSE) {
                throw exception;
            }
            checkpoints.failAssignment(
                    execution,
                    "INVALID_RESPONSE",
                    true
            );
        }
    }

    private DocumentCategoryDecision requireDecision(DocumentCategoryDecision decision) {
        if (decision == null) {
            throw new DocumentException(DocumentErrorCode.INVALID_DOCUMENT_CATEGORY_RESPONSE);
        }
        return decision;
    }

    private boolean isRetryable(ErrorCode code) {
        return code == LlmErrorCode.LLM_TIMEOUT || code == LlmErrorCode.LLM_RATE_LIMITED
                || code == LlmErrorCode.LLM_INVALID_RESPONSE;
    }
}
