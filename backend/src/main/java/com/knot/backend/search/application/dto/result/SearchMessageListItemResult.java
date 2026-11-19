package com.knot.backend.search.application.dto.result;

import com.knot.backend.search.application.SearchAnswerContentPolicy;
import com.knot.backend.search.application.SearchAnswerContentPolicy.EvidenceStatus;
import com.knot.backend.search.domain.model.SearchMessage;
import com.knot.backend.search.domain.model.SearchMessageRole;
import com.knot.backend.search.domain.model.SearchMessageStatus;
import java.time.Instant;
import java.util.List;

public record SearchMessageListItemResult(
        long id,
        SearchMessageRole role,
        int sequence,
        String content,
        SearchMessageStatus status,
        boolean retryable,
        Instant createdAt,
        List<SearchEvidenceItemResult> evidences,
        SearchDocumentSelection documentSelection
) {

    public SearchMessageListItemResult {
        evidences = List.copyOf(evidences);
    }

    public static SearchMessageListItemResult from(
            SearchMessage message,
            List<SearchEvidenceItemResult> evidences
    ) {
        return from(
                message,
                evidences,
                null
        );
    }

    public static SearchMessageListItemResult from(
            SearchMessage message,
            List<SearchEvidenceItemResult> evidences,
            SearchDocumentListResult documentList
    ) {
        return from(
                message,
                evidences,
                documentList,
                EvidenceStatus.AVAILABLE
        );
    }

    public static SearchMessageListItemResult from(
            SearchMessage message,
            List<SearchEvidenceItemResult> evidences,
            SearchDocumentListResult documentList,
            EvidenceStatus evidenceStatus
    ) {
        String content = SearchAnswerContentPolicy.content(
                message.getContent(),
                evidenceStatus
        );
        SearchDocumentSelection selection = null;
        if (documentList != null && evidenceStatus != EvidenceStatus.SOURCE_UNAVAILABLE) {
            content = documentList.content();
            selection = documentList.selectedDocument();
        }
        return new SearchMessageListItemResult(
                message.getId(),
                message.getRole(),
                message.getSequence(),
                content,
                message.getStatus(),
                message.isRetryable(),
                message.getCreatedAt(),
                SearchAnswerContentPolicy.evidences(
                        evidences,
                        evidenceStatus
                ),
                selection
        );
    }
}
