package com.knot.backend.search.application;

import com.knot.backend.search.application.SearchAnswerContentPolicy.EvidenceStatus;
import com.knot.backend.search.application.dto.result.SearchAnswerNotionResult;
import com.knot.backend.search.application.dto.result.SearchAnswerSnapshotResult;
import com.knot.backend.search.application.dto.result.SearchDocumentListResult;
import com.knot.backend.search.application.dto.result.SearchDocumentSelection;
import com.knot.backend.search.application.dto.result.SearchEvidenceItemResult;
import com.knot.backend.search.application.notion.SearchAnswerNotionReader;
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

@Service
@RequiredArgsConstructor
public class SearchAnswerSnapshotService {

    private final SearchRepository repository;
    private final WorkspaceMemberRepository memberships;
    private final SearchMessageListQuery messages;
    private final SearchHistoryReadTransaction historyRead;
    private final SearchAnswerNotionReader notions;

    public SearchAnswerSnapshotResult read(
            long workspaceId,
            long memberId,
            long conversationId,
            long answerId
    ) {
        SearchAnswerSnapshotResult snapshot = historyRead.read(
                () -> readScoped(
                        workspaceId,
                        memberId,
                        conversationId,
                        answerId
                )
        );
        return protect(
                workspaceId,
                memberId,
                conversationId,
                snapshot,
                true
        );
    }

    private SearchAnswerSnapshotResult readScoped(
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
        LiveSnapshot snapshot = historyRead.read(
                () -> readLive(
                        workspaceId,
                        memberId,
                        answerId
                )
        );
        return protect(
                workspaceId,
                memberId,
                snapshot.conversationId(),
                snapshot.answer(),
                false
        );
    }

    private LiveSnapshot readLive(
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
        return new LiveSnapshot(
                conversation.getId(),
                readSnapshot(
                        workspaceId,
                        memberId,
                        conversation.getId(),
                        answerId,
                        answer,
                        execution,
                        conversation
                )
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
                selection,
                false,
                notion(
                        answer,
                        conversationId,
                        answerId
                )
        );
    }

    /**
     * 노션 이벤트는 끝 이벤트와 함께 보내므로 생성 중인 답변은 읽지 않는다.
     */
    private SearchAnswerNotionResult notion(
            SearchMessage answer,
            long conversationId,
            long answerId
    ) {
        if (answer.getStatus() == SearchMessageStatus.STREAMING) {
            return SearchAnswerNotionResult.EMPTY;
        }
        return notions.read(
                conversationId,
                List.of(answerId)
        )
                .getOrDefault(
                        answerId,
                        SearchAnswerNotionResult.EMPTY
                );
    }

    private SearchAnswerSnapshotResult protect(
            long workspaceId,
            long memberId,
            long conversationId,
            SearchAnswerSnapshotResult snapshot,
            boolean compareVersions
    ) {
        if (snapshot.status() != SearchMessageStatus.COMPLETED) {
            return snapshot;
        }
        EvidenceStatus status = messages.findEvidenceStatuses(
                workspaceId,
                memberId,
                conversationId,
                List.of(snapshot.answerId()),
                compareVersions
        )
                .getOrDefault(
                        snapshot.answerId(),
                        EvidenceStatus.AVAILABLE
                );
        return snapshot.withEvidenceStatus(status);
    }

    private record LiveSnapshot(
            long conversationId,
            SearchAnswerSnapshotResult answer
    ) {
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
