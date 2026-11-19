package com.knot.backend.search.infrastructure.persistence;

import com.knot.backend.search.application.SearchMessageListQuery;
import com.knot.backend.search.application.dto.result.SearchDocumentListResult;
import com.knot.backend.search.application.dto.result.SearchEvidenceItemResult;
import com.knot.backend.search.domain.model.SearchConversation;
import com.knot.backend.search.domain.model.SearchMessage;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class SearchMessageListQueryAdapter implements SearchMessageListQuery {

    private final SearchMessageListJpaRepository messages;
    private final SearchEvidenceReadJpaRepository evidences;
    private final SearchDocumentListStore documentLists;

    @Override
    public Map<Long, SearchDocumentListResult> findDocumentLists(
            long workspaceId,
            long memberId,
            long conversationId,
            List<Long> answerIds
    ) {
        return documentLists.findAnswers(
                workspaceId,
                memberId,
                conversationId,
                answerIds
        );
    }

    @Override
    public Optional<SearchConversation> findConversation(long conversationId) {
        return messages.findConversation(conversationId);
    }

    @Override
    public List<SearchMessage> findPage(
            long workspaceId,
            long memberId,
            long conversationId,
            Integer beforeSequence,
            int size
    ) {
        return messages.findPage(
                workspaceId,
                memberId,
                conversationId,
                beforeSequence,
                PageRequest.of(
                        0,
                        size + 1
                )
        );
    }

    @Override
    public Map<Long, List<SearchEvidenceItemResult>> findEvidences(
            long workspaceId,
            long memberId,
            long conversationId,
            List<Long> answerIds
    ) {
        if (answerIds.isEmpty()) {
            return Map.of();
        }
        List<SearchEvidenceRow> rows = evidences.findForMessages(
                workspaceId,
                memberId,
                conversationId,
                answerIds
        );
        Map<Long, List<SearchEvidenceItemResult>> grouped = new HashMap<>();
        for (SearchEvidenceRow row : rows) {
            List<SearchEvidenceItemResult> items = grouped.computeIfAbsent(
                    row.messageId(),
                    ignored -> new ArrayList<>()
            );
            items.add(
                    new SearchEvidenceItemResult(
                            row.documentId(),
                            row.title(),
                            row.topic(),
                            row.rank()
                    )
            );
        }
        return grouped;
    }
}
