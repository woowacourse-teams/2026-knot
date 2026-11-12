package com.knot.backend.search.application;

import com.knot.backend.search.domain.model.SearchAnswerExecution;
import com.knot.backend.search.domain.model.SearchConversation;
import com.knot.backend.search.domain.repository.SearchRepository;
import com.knot.backend.search.domain.SearchToolException;
import com.knot.backend.search.domain.SearchScopeExpiredException;
import com.knot.backend.workspace.domain.repository.WorkspaceMemberRepository;
import com.knot.backend.workspace.domain.repository.WorkspaceRepository;
import java.time.Clock;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SearchToolAccess {

    private final SearchRepository searches;
    private final WorkspaceRepository workspaces;
    private final WorkspaceMemberRepository memberships;
    private final Clock clock;

    public void validate(SearchToolScope scope) {
        validateTime(scope);
        if (workspaces.findById(scope.workspaceId())
                .isEmpty()) {
            throw denied();
        }
        if (!memberships.existsByWorkspaceIdAndMemberId(
                scope.workspaceId(),
                scope.memberId()
        )) {
            throw denied();
        }
        SearchConversation conversation = searches.findConversation(scope.conversationId())
                .orElseThrow(this::denied);
        if (conversation.getWorkspaceId() != scope.workspaceId() || conversation.getMemberId() != scope.memberId()) {
            throw denied();
        }
        SearchAnswerExecution execution = searches.findExecution(scope.answerId())
                .orElseThrow(this::denied);
        if (execution.getConversationId() != scope.conversationId()
                || execution.getQuestionId() != scope.questionId()) {
            throw denied();
        }
        if (!execution.isCurrent(
                scope.token(),
                clock.instant()
        )) {
            throw denied();
        }
        if (scope.expiresAt()
                .isAfter(execution.getDeadlineAt())) {
            throw denied();
        }
    }

    private void validateTime(SearchToolScope scope) {
        if (scope.startedAt()
                .isAfter(clock.instant())) {
            throw denied();
        }
        if (!clock.instant()
                .isBefore(scope.expiresAt())) {
            throw new SearchScopeExpiredException();
        }
    }

    private SearchToolException denied() {
        return new SearchToolException("SEARCH_ACCESS_DENIED");
    }
}
