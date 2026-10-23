package com.knot.backend.document.application.dto.result;

import com.knot.backend.document.domain.model.DocumentGenerationJobStage;
import com.knot.backend.document.domain.model.DocumentGenerationPhase;

public record DocumentGenerationExecution(
        long workspaceId,
        long jobId,
        int attemptCount,
        DocumentGenerationJobStage stage,
        String transcriptContent,
        String topic,
        DocumentGenerationPhase processingPhase
) {

    public DocumentGenerationExecution(
            long workspaceId,
            long jobId,
            int attemptCount,
            DocumentGenerationJobStage stage,
            String transcriptContent,
            String topic
    ) {
        this(
                workspaceId,
                jobId,
                attemptCount,
                stage,
                transcriptContent,
                topic,
                DocumentGenerationPhase.WRITE_BODY
        );
    }

    @Override
    public String toString() {
        return "DocumentGenerationExecution[jobId=" + jobId + ", stage=" + stage + ", attemptCount=" + attemptCount
                + "]";
    }
}
