package com.knot.backend.search.application;

import com.knot.backend.search.application.dto.result.SearchAnswerSnapshotResult;
import com.knot.backend.search.application.dto.result.SearchDocumentListResult;
import com.knot.backend.search.application.dto.result.SearchDocumentSelection;
import com.knot.backend.search.application.dto.result.SearchEvidenceItemResult;
import com.knot.backend.search.domain.model.SearchAnswerExecution;
import com.knot.backend.search.domain.model.SearchConversation;
import com.knot.backend.search.domain.model.SearchMessage;
import com.knot.backend.search.domain.model.SearchMessageRole;
import com.knot.backend.search.domain.model.SearchMessageStatus;
import com.knot.backend.search.domain.repository.SearchRepository;
import com.knot.backend.search.domain.SearchErrorCode;
import com.knot.backend.search.domain.SearchException;
import com.knot.backend.workspace.domain.repository.WorkspaceMemberRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class SearchAnswerSnapshotService {

    private final SearchRepository repository;
    private final WorkspaceMemberRepository memberships;
    private final SearchMessageListQuery messages;

    public SearchAnswerSnapshotResult read(
            long workspaceId,
            long memberId,
            long conversationId,
            long answerId
    ) {
        SearchConversation conversation = repository.findConversation(conversationId)
                .orElseThrow(() -> new SearchException(SearchErrorCode.ANSWER_NOT_FOUND));
        validateAccess(
                workspaceId,
                memberId,
                conversation
        );
        SearchMessage answer = repository.findMessage(answerId)
                .orElseThrow(() -> new SearchException(SearchErrorCode.ANSWER_NOT_FOUND));
        validateAnswer(
                answer,
                conversationId
        );
        SearchAnswerExecution execution = repository.findExecution(answerId)
                .orElseThrow(() -> new SearchException(SearchErrorCode.ANSWER_GENERATION_UNAVAILABLE));
        if (execution.getConversationId() != conversationId) {
            throw new SearchException(SearchErrorCode.ANSWER_GENERATION_UNAVAILABLE);
        }
        return readSnapshot(
                workspaceId,
                memberId,
                conversationId,
                answerId,
                answer,
                execution,
                conversation
        );
    }

    public SearchAnswerSnapshotResult read(
            long workspaceId,
            long memberId,
            long answerId
    ) {
        SearchAnswerExecution execution = repository.findExecution(answerId)
                .orElseThrow();
        SearchConversation conversation = repository.findConversation(execution.getConversationId())
                .orElseThrow();
        validateAccess(
                workspaceId,
                memberId,
                conversation
        );
        SearchMessage answer = repository.findMessage(answerId)
                .orElseThrow();
        return readSnapshot(
                workspaceId,
                memberId,
                conversation.getId(),
                answerId,
                answer,
                execution,
                conversation
        );
    }

    private SearchAnswerSnapshotResult readSnapshot(
            long workspaceId,
            long memberId,
            long conversationId,
            long answerId,
            SearchMessage answer,
            SearchAnswerExecution execution,
            SearchConversation conversation
    ) {
        List<SearchEvidenceItemResult> evidences = messages.findEvidences(
                workspaceId,
                memberId,
                conversationId,
                List.of(answerId)
        )
                .getOrDefault(
                        answerId,
                        List.of()
                );
        SearchDocumentListResult list = null;
        if (answer.getStatus() == SearchMessageStatus.COMPLETED) {
            list = messages.findDocumentLists(
                    workspaceId,
                    memberId,
                    conversationId,
                    List.of(answerId)
            )
                    .get(answerId);
        }
        String content = answer.getContent();
        SearchDocumentSelection selection = null;
        if (list != null) {
            content = list.content();
            selection = list.selectedDocument();
        }
        return new SearchAnswerSnapshotResult(
                answerId,
                content,
                answer.getStatus(),
                answer.getFailureCode(),
                execution.getExecutionToken(),
                execution.getStage(),
                execution.getExcludedCount(),
                List.copyOf(evidences),
                repository.findLatestReceipt(answerId)
                        .map(receipt -> receipt.getId())
                        .orElse(0L),
                conversation.getUpdatedAt(),
                selection
        );
    }

    private void validateAnswer(
            SearchMessage answer,
            long conversationId
    ) {
        if (answer.getRole() != SearchMessageRole.ASSISTANT) {
            throw new SearchException(SearchErrorCode.ANSWER_NOT_FOUND);
        }
        if (answer.getConversationId() != conversationId) {
            throw new SearchException(SearchErrorCode.ANSWER_NOT_FOUND);
        }
    }

    private void validateAccess(
            long workspaceId,
            long memberId,
            SearchConversation conversation
    ) {
        if (conversation.getWorkspaceId() != workspaceId || conversation.getMemberId() != memberId) {
            throw new SearchException(SearchErrorCode.SEARCH_ACCESS_DENIED);
        }
        if (!memberships.existsByWorkspaceIdAndMemberId(
                workspaceId,
                memberId
        )) {
            throw new SearchException(SearchErrorCode.SEARCH_ACCESS_DENIED);
        }
    }
}
