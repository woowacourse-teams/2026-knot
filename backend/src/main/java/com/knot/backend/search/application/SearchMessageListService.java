package com.knot.backend.search.application;

import com.knot.backend.search.application.dto.query.SearchMessageListParameters;
import com.knot.backend.search.application.dto.result.SearchDocumentListResult;
import com.knot.backend.search.application.dto.result.SearchEvidenceItemResult;
import com.knot.backend.search.application.dto.result.SearchMessageListItemResult;
import com.knot.backend.search.application.dto.result.SearchMessageListResult;
import com.knot.backend.search.domain.model.SearchConversation;
import com.knot.backend.search.domain.SearchErrorCode;
import com.knot.backend.search.domain.SearchException;
import com.knot.backend.search.domain.model.SearchMessage;
import com.knot.backend.search.domain.model.SearchMessageRole;
import com.knot.backend.workspace.domain.repository.WorkspaceMemberRepository;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class SearchMessageListService {

    private final WorkspaceMemberRepository memberships;
    private final SearchMessageListQuery query;

    public SearchMessageListResult find(
            long workspaceId,
            long memberId,
            long conversationId,
            SearchMessageListParameters parameters
    ) {
        validatePathIds(
                workspaceId,
                conversationId
        );
        validateMembership(
                workspaceId,
                memberId
        );
        SearchConversation conversation = query.findConversation(conversationId)
                .orElseThrow(() -> new SearchException(SearchErrorCode.CONVERSATION_NOT_FOUND));
        validateConversationScope(
                conversation,
                workspaceId,
                memberId
        );
        List<SearchMessage> page = query.findPage(
                workspaceId,
                memberId,
                conversationId,
                parameters.beforeSequence(),
                parameters.size()
        );
        List<SearchMessage> selected = selectPage(
                page,
                parameters.size()
        );
        Map<Long, List<SearchEvidenceItemResult>> evidences = findEvidences(
                workspaceId,
                memberId,
                conversationId,
                selected
        );
        List<Long> answerIds = answerIds(selected);
        Map<Long, SearchDocumentListResult> documentLists = Map.of();
        if (!answerIds.isEmpty()) {
            documentLists = query.findDocumentLists(
                    workspaceId,
                    memberId,
                    conversationId,
                    answerIds
            );
        }
        boolean hasPrevious = hasPrevious(
                page,
                parameters.size()
        );
        return new SearchMessageListResult(
                conversationId,
                assembleItems(
                        selected,
                        evidences,
                        documentLists
                ),
                hasPrevious,
                createPreviousCursor(
                        selected,
                        hasPrevious
                )
        );
    }

    private void validatePathIds(
            long workspaceId,
            long conversationId
    ) {
        validatePositiveId(workspaceId);
        validatePositiveId(conversationId);
    }

    private void validatePositiveId(long id) {
        if (id <= 0) {
            throw new SearchException(SearchErrorCode.INVALID_PARAMETER);
        }
    }

    private void validateMembership(
            long workspaceId,
            long memberId
    ) {
        if (!memberships.existsByWorkspaceIdAndMemberId(
                workspaceId,
                memberId
        )) {
            throw new SearchException(SearchErrorCode.SEARCH_ACCESS_DENIED);
        }
    }

    private void validateConversationScope(
            SearchConversation conversation,
            long workspaceId,
            long memberId
    ) {
        if (conversation.getWorkspaceId() != workspaceId) {
            throw new SearchException(SearchErrorCode.SEARCH_ACCESS_DENIED);
        }
        if (conversation.getMemberId() != memberId) {
            throw new SearchException(SearchErrorCode.SEARCH_ACCESS_DENIED);
        }
    }

    private List<SearchMessage> selectPage(
            List<SearchMessage> page,
            int size
    ) {
        if (page.size() > size) {
            return page.subList(
                    0,
                    size
            );
        }
        return page;
    }

    private Map<Long, List<SearchEvidenceItemResult>> findEvidences(
            long workspaceId,
            long memberId,
            long conversationId,
            List<SearchMessage> selected
    ) {
        List<Long> answerIds = answerIds(selected);
        if (answerIds.isEmpty()) {
            return Map.of();
        }
        return query.findEvidences(
                workspaceId,
                memberId,
                conversationId,
                answerIds
        );
    }

    private List<Long> answerIds(List<SearchMessage> selected) {
        return selected.stream()
                .filter(message -> message.getRole() == SearchMessageRole.ASSISTANT)
                .map(SearchMessage::getId)
                .toList();
    }

    private List<SearchMessageListItemResult> assembleItems(
            List<SearchMessage> selected,
            Map<Long, List<SearchEvidenceItemResult>> evidences,
            Map<Long, SearchDocumentListResult> documentLists
    ) {
        return selected.stream()
                .sorted(Comparator.comparingInt(SearchMessage::getSequence))
                .map(
                        message -> SearchMessageListItemResult.from(
                                message,
                                evidenceFor(
                                        message,
                                        evidences
                                ),
                                documentLists.get(message.getId())
                        )
                )
                .toList();
    }

    private List<SearchEvidenceItemResult> evidenceFor(
            SearchMessage message,
            Map<Long, List<SearchEvidenceItemResult>> evidences
    ) {
        if (message.getRole() == SearchMessageRole.USER) {
            return List.of();
        }
        return evidences.getOrDefault(
                message.getId(),
                List.of()
        );
    }

    private boolean hasPrevious(
            List<SearchMessage> page,
            int size
    ) {
        return page.size() > size;
    }

    private String createPreviousCursor(
            List<SearchMessage> selected,
            boolean hasPrevious
    ) {
        if (!hasPrevious) {
            return null;
        }
        SearchMessage earliest = selected.getLast();
        return Integer.toString(earliest.getSequence());
    }
}
