package com.knot.backend.search.application.dto.result;

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
        String content = message.getContent();
        SearchDocumentSelection selection = null;
        if (documentList != null) {
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
                evidences,
                selection
        );
    }
}
