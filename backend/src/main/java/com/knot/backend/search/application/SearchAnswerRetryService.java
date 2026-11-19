package com.knot.backend.search.application;

import com.knot.backend.search.application.dto.result.SearchQuestionAcceptedResult;
import com.knot.backend.search.domain.model.SearchAnswerExecution;
import com.knot.backend.search.domain.model.SearchConversation;
import com.knot.backend.search.domain.model.SearchMessage;
import com.knot.backend.search.domain.model.SearchMessageRole;
import com.knot.backend.search.domain.model.SearchExecutionState;
import com.knot.backend.search.domain.repository.SearchRepository;
import com.knot.backend.search.domain.model.SearchRequestReceipt;
import com.knot.backend.search.domain.SearchErrorCode;
import com.knot.backend.search.domain.SearchException;
import com.knot.backend.workspace.domain.repository.WorkspaceRepository;
import com.knot.backend.workspace.domain.repository.WorkspaceMemberRepository;
import com.knot.backend.workspace.domain.model.WorkspaceMember;
import java.time.Clock;
import java.util.UUID;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class SearchAnswerRetryService {

    private final WorkspaceRepository workspaces;
    private final WorkspaceMemberRepository memberships;
    private final SearchRepository repository;
    private final SearchGenerationAvailability availability;
    private final Clock clock;

    @Transactional
    public SearchQuestionAcceptedResult acceptRetry(
            long workspaceId,
            long memberId,
            long conversationId,
            long questionId,
            UUID requestId
    ) {
        validateIdentifier(workspaceId);
        validateIdentifier(memberId);
        validateIdentifier(conversationId);
        validateIdentifier(questionId);
        if (requestId == null) {
            throw new SearchException(SearchErrorCode.VALIDATION_ERROR);
        }
        lockMembership(
                workspaceId,
                memberId
        );
        SearchConversation conversation = repository.findConversationForUpdate(conversationId)
                .orElseThrow(() -> new SearchException(SearchErrorCode.QUESTION_NOT_FOUND));
        validateOwner(
                conversation,
                workspaceId,
                memberId
        );
        Optional<SearchRequestReceipt> existing = repository.findRetryReceipt(
                workspaceId,
                memberId,
                requestId
        );
        if (existing.isPresent()) {
            return restore(
                    existing.orElseThrow(),
                    conversationId,
                    questionId
            );
        }
        SearchMessage answer = pairedAnswer(
                conversationId,
                questionId
        );
        if (repository.existsStreamingAnswer(conversationId)) {
            throw new SearchException(SearchErrorCode.ANSWER_ALREADY_IN_PROGRESS);
        }
        if (!answer.isRetryable()) {
            throw new SearchException(SearchErrorCode.ANSWER_NOT_FAILED);
        }
        availability.requireAvailable();
        SearchAnswerExecution execution = repository.findExecutionForUpdate(answer.getId())
                .orElseThrow(() -> new SearchException(SearchErrorCode.INVALID_SEARCH_STATE));
        validateExecution(
                execution,
                conversationId,
                questionId
        );
        answer.retry();
        execution.retry();
        repository.deleteEvidences(answer.getId());
        SearchRequestReceipt receipt = repository.save(
                SearchRequestReceipt.retry(
                        workspaceId,
                        memberId,
                        requestId,
                        conversationId,
                        questionId,
                        answer.getId(),
                        clock.instant()
                )
        );
        conversation.recordMessageActivity(receipt.getCreatedAt());
        repository.flush();
        return accepted(receipt);
    }

    private void validateIdentifier(long id) {
        if (id <= 0) {
            throw new SearchException(SearchErrorCode.INVALID_PARAMETER);
        }
    }

    private void lockMembership(
            long workspaceId,
            long memberId
    ) {
        workspaces.findByIdForUpdate(workspaceId)
                .orElseThrow(() -> new SearchException(SearchErrorCode.SEARCH_ACCESS_DENIED));
        WorkspaceMember membership = memberships.findLatestByWorkspaceIdAndMemberIdForUpdate(
                workspaceId,
                memberId
        )
                .orElseThrow(() -> new SearchException(SearchErrorCode.SEARCH_ACCESS_DENIED));
        if (!membership.isActive()) {
            throw new SearchException(SearchErrorCode.SEARCH_ACCESS_DENIED);
        }
    }

    private void validateOwner(
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

    private SearchMessage pairedAnswer(
            long conversationId,
            long questionId
    ) {
        SearchMessage question = repository.findMessage(questionId)
                .orElseThrow(() -> new SearchException(SearchErrorCode.QUESTION_NOT_FOUND));
        if (question.getConversationId() != conversationId) {
            throw new SearchException(SearchErrorCode.QUESTION_NOT_FOUND);
        }
        if (question.getRole() != SearchMessageRole.USER) {
            throw new SearchException(SearchErrorCode.QUESTION_NOT_FOUND);
        }
        if (question.getSequence() == Integer.MAX_VALUE) {
            throw new SearchException(SearchErrorCode.QUESTION_NOT_FOUND);
        }
        SearchMessage answer = repository.findMessageBySequence(
                conversationId,
                question.getSequence() + 1
        )
                .orElseThrow(() -> new SearchException(SearchErrorCode.QUESTION_NOT_FOUND));
        if (answer.getRole() != SearchMessageRole.ASSISTANT) {
            throw new SearchException(SearchErrorCode.QUESTION_NOT_FOUND);
        }
        return answer;
    }

    private void validateExecution(
            SearchAnswerExecution execution,
            long conversationId,
            long questionId
    ) {
        if (execution.getConversationId() != conversationId || execution.getQuestionId() != questionId) {
            throw new SearchException(SearchErrorCode.INVALID_SEARCH_STATE);
        }
        if (execution.getState() != SearchExecutionState.FINISHED) {
            throw new SearchException(SearchErrorCode.INVALID_SEARCH_STATE);
        }
    }

    private SearchQuestionAcceptedResult restore(
            SearchRequestReceipt receipt,
            long conversationId,
            long questionId
    ) {
        if (receipt.getConversationId() != conversationId || receipt.getQuestionMessageId() != questionId) {
            throw new SearchException(SearchErrorCode.REQUEST_ID_CONFLICT);
        }
        return accepted(receipt);
    }

    private SearchQuestionAcceptedResult accepted(SearchRequestReceipt receipt) {
        return new SearchQuestionAcceptedResult(
                receipt.getConversationId(),
                receipt.getQuestionMessageId(),
                receipt.getAnswerMessageId(),
                receipt.getId()
        );
    }
}
