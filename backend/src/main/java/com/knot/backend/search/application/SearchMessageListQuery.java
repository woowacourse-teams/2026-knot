package com.knot.backend.search.application;

import com.knot.backend.search.application.dto.result.SearchEvidenceItemResult;
import com.knot.backend.search.application.SearchAnswerContentPolicy.EvidenceStatus;
import com.knot.backend.search.application.dto.result.SearchDocumentListResult;
import com.knot.backend.search.domain.model.SearchConversation;
import com.knot.backend.search.domain.model.SearchMessage;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public interface SearchMessageListQuery {

    Optional<SearchConversation> findConversation(long conversationId);

    Map<Long, SearchDocumentListResult> findDocumentLists(
            long workspaceId,
            long memberId,
            long conversationId,
            List<Long> answerIds
    );

    List<SearchMessage> findPage(
            long workspaceId,
            long memberId,
            long conversationId,
            Integer beforeSequence,
            int size
    );

    Map<Long, EvidenceStatus> findEvidenceStatuses(
            long workspaceId,
            long memberId,
            long conversationId,
            List<Long> answerIds,
            boolean compareVersions
    );

    Map<Long, List<SearchEvidenceItemResult>> findEvidences(
            long workspaceId,
            long memberId,
            long conversationId,
            List<Long> answerIds
    );
}
