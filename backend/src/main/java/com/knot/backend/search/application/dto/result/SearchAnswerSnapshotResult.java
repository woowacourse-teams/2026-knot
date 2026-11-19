package com.knot.backend.search.application.dto.result;

import com.knot.backend.search.application.SearchAnswerContentPolicy;
import com.knot.backend.search.application.SearchAnswerContentPolicy.EvidenceStatus;
import com.knot.backend.search.domain.model.SearchMessageStatus;
import com.knot.backend.search.domain.model.SearchProgressStage;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record SearchAnswerSnapshotResult(
        long answerId,
        String content,
        SearchMessageStatus status,
        String failureCode,
        UUID executionToken,
        SearchProgressStage stage,
        Integer excludedCount,
        List<SearchEvidenceItemResult> evidences,
        long receiptId,
        Instant contentUpdatedAt,
        SearchDocumentSelection documentSelection,
        boolean sourceUnavailable
) {
    public SearchAnswerSnapshotResult withEvidenceStatus(EvidenceStatus evidenceStatus) {
        if (evidenceStatus == EvidenceStatus.AVAILABLE) {
            return this;
        }
        boolean hidden = evidenceStatus == EvidenceStatus.SOURCE_UNAVAILABLE;
        return new SearchAnswerSnapshotResult(
                answerId,
                SearchAnswerContentPolicy.content(
                        content,
                        evidenceStatus
                ),
                status,
                failureCode,
                executionToken,
                stage,
                excludedCount,
                SearchAnswerContentPolicy.evidences(
                        evidences,
                        evidenceStatus
                ),
                receiptId,
                contentUpdatedAt,
                hidden ? null : documentSelection,
                hidden
        );
    }

    public SearchAnswerSnapshotResult(
            long answerId,
            String content,
            SearchMessageStatus status,
            String failureCode,
            UUID executionToken,
            SearchProgressStage stage,
            Integer excludedCount,
            List<SearchEvidenceItemResult> evidences,
            long receiptId,
            Instant contentUpdatedAt
    ) {
        this(
                answerId,
                content,
                status,
                failureCode,
                executionToken,
                stage,
                excludedCount,
                evidences,
                receiptId,
                contentUpdatedAt,
                null,
                false
        );
    }

    public SearchAnswerSnapshotResult(
            long answerId,
            String content,
            SearchMessageStatus status,
            String failureCode,
            UUID executionToken,
            SearchProgressStage stage,
            Integer excludedCount,
            List<SearchEvidenceItemResult> evidences,
            long receiptId
    ) {
        this(
                answerId,
                content,
                status,
                failureCode,
                executionToken,
                stage,
                excludedCount,
                evidences,
                receiptId,
                null
        );
    }

    public SearchAnswerSnapshotResult(
            long answerId,
            String content,
            SearchMessageStatus status,
            String failureCode,
            UUID executionToken,
            SearchProgressStage stage,
            Integer excludedCount,
            List<SearchEvidenceItemResult> evidences
    ) {
        this(
                answerId,
                content,
                status,
                failureCode,
                executionToken,
                stage,
                excludedCount,
                evidences,
                0,
                null
        );
    }
}
