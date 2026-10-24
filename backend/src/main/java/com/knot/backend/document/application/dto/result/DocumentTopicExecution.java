package com.knot.backend.document.application.dto.result;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record DocumentTopicExecution(
        long workspaceId,
        long operationId,
        UUID token,
        Instant deadline,
        boolean consolidate,
        List<DocumentTopicTarget> targets,
        List<DocumentTopicCandidate> categories
) {

    public DocumentTopicExecution firstHalf() {
        int count = Math.max(
                1,
                targets.size() / 2
        );
        return slice(
                0,
                count
        );
    }

    public DocumentTopicExecution secondHalf() {
        int count = Math.max(
                1,
                targets.size() / 2
        );
        return slice(
                count,
                targets.size()
        );
    }

    private DocumentTopicExecution slice(
            int from,
            int to
    ) {
        return new DocumentTopicExecution(
                workspaceId,
                operationId,
                token,
                deadline,
                false,
                List.copyOf(
                        targets.subList(
                                from,
                                to
                        )
                ),
                categories
        );
    }
}
