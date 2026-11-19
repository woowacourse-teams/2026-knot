package com.knot.backend.search.application;

import com.knot.backend.search.application.dto.command.SearchQuestionCommand;
import com.knot.backend.search.application.dto.query.SearchDocumentListRequest;
import com.knot.backend.search.application.dto.result.SearchQuestionAcceptedResult;
import com.knot.backend.search.domain.model.SearchAnswerExecution;
import com.knot.backend.search.domain.model.SearchConversation;
import com.knot.backend.search.domain.SearchErrorCode;
import com.knot.backend.search.domain.SearchException;
import com.knot.backend.search.domain.model.SearchMessage;
import com.knot.backend.search.domain.model.SearchMessageRole;
import com.knot.backend.search.domain.repository.SearchRepository;
import com.knot.backend.search.domain.model.SearchRequestReceipt;
import com.knot.backend.workspace.domain.repository.WorkspaceRepository;
import com.knot.backend.workspace.domain.repository.WorkspaceMemberRepository;
import com.knot.backend.workspace.domain.model.WorkspaceMember;
import com.knot.backend.workspace.domain.WorkspaceErrorCode;
import com.knot.backend.workspace.domain.WorkspaceException;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class SearchQuestionIntakeService {

    private final WorkspaceRepository workspaces;
    private final WorkspaceMemberRepository memberships;
    private final SearchRepository repository;
    private final SearchGenerationAvailability availability;
    private final Clock clock;

    @Transactional
    public SearchQuestionAcceptedResult acceptFirst(
            long workspaceId,
            long memberId,
            SearchQuestionCommand command
    ) {
        validateScope(
                workspaceId,
                memberId
        );
        lockMembership(
                workspaceId,
                memberId
        );
        Optional<SearchRequestReceipt> existing = repository.findFirstReceipt(
                workspaceId,
                memberId,
                command.requestId()
        );
        if (existing.isPresent()) {
            return restore(
                    existing.get(),
                    command.content()
            );
        }
        requireAvailable(command);
        return create(
                workspaceId,
                memberId,
                command
        );
    }

    private void validateScope(
            long workspaceId,
            long memberId
    ) {
        if (workspaceId <= 0 || memberId <= 0) {
            throw new SearchException(SearchErrorCode.INVALID_PARAMETER);
        }
    }

    @Transactional
    public SearchQuestionAcceptedResult acceptNext(
            long workspaceId,
            long memberId,
            long conversationId,
            SearchQuestionCommand command
    ) {
        validateScope(
                workspaceId,
                memberId
        );
        validateConversationId(conversationId);
        lockQuestionMembership(
                workspaceId,
                memberId
        );
        SearchConversation conversation = repository.findConversationForUpdate(conversationId)
                .orElseThrow(() -> new SearchException(SearchErrorCode.CONVERSATION_NOT_FOUND));
        validateConversationScope(
                conversation,
                workspaceId,
                memberId
        );
        Optional<SearchRequestReceipt> existing = repository.findQuestionReceipt(
                workspaceId,
                memberId,
                conversationId,
                command.requestId()
        );
        if (existing.isPresent()) {
            return restore(
                    existing.orElseThrow(),
                    command.content()
            );
        }
        requireNoStreamingAnswer(conversationId);
        requireAvailable(command);
        return appendQuestion(
                conversation,
                command
        );
    }

    private void requireAvailable(SearchQuestionCommand command) {
        if (SearchDocumentListRequest.parse(command.content())
                .isPresent()) {
            availability.requireWorkerAvailable();
            return;
        }
        availability.requireAvailable();
    }

    private void validateConversationId(long conversationId) {
        if (conversationId <= 0) {
            throw new SearchException(SearchErrorCode.INVALID_PARAMETER);
        }
    }

    private void lockQuestionMembership(
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

    private void validateConversationScope(
            SearchConversation conversation,
            long workspaceId,
            long memberId
    ) {
        if (conversation.getWorkspaceId() != workspaceId || conversation.getMemberId() != memberId) {
            throw new SearchException(SearchErrorCode.SEARCH_ACCESS_DENIED);
        }
    }

    private void requireNoStreamingAnswer(long conversationId) {
        if (repository.existsStreamingAnswer(conversationId)) {
            throw new SearchException(SearchErrorCode.ANSWER_ALREADY_IN_PROGRESS);
        }
    }

    private int nextQuestionSequence(long conversationId) {
        SearchMessage last = repository.findLastMessage(conversationId)
                .orElseThrow(() -> new SearchException(SearchErrorCode.INVALID_SEARCH_STATE));
        if (last.getRole() != SearchMessageRole.ASSISTANT) {
            throw new SearchException(SearchErrorCode.INVALID_SEARCH_STATE);
        }
        if (last.getSequence() > Integer.MAX_VALUE - 2) {
            throw new SearchException(SearchErrorCode.INVALID_SEARCH_STATE);
        }
        return last.getSequence() + 1;
    }

    private SearchQuestionAcceptedResult appendQuestion(
            SearchConversation conversation,
            SearchQuestionCommand command
    ) {
        long conversationId = conversation.getId();
        int sequence = nextQuestionSequence(conversationId);
        Instant acceptedAt = clock.instant();
        SearchMessage question = repository.save(
                SearchMessage.receiveQuestion(
                        conversationId,
                        sequence,
                        command.content(),
                        acceptedAt
                )
        );
        SearchMessage answer = repository.save(
                SearchMessage.startAnswer(
                        conversationId,
                        sequence + 1,
                        acceptedAt
                )
        );
        SearchRequestReceipt receipt = repository.save(
                SearchRequestReceipt.question(
                        conversation.getWorkspaceId(),
                        conversation.getMemberId(),
                        command.requestId(),
                        conversationId,
                        question.getId(),
                        answer.getId(),
                        acceptedAt
                )
        );
        repository.save(
                SearchAnswerExecution.queue(
                        conversationId,
                        question.getId(),
                        answer.getId(),
                        acceptedAt
                )
        );
        conversation.recordMessageActivity(acceptedAt);
        repository.flush();
        return new SearchQuestionAcceptedResult(
                conversationId,
                question.getId(),
                answer.getId(),
                receipt.getId()
        );
    }

    private void lockMembership(
            long workspaceId,
            long memberId
    ) {
        workspaces.findByIdForUpdate(workspaceId)
                .orElseThrow(() -> new WorkspaceException(WorkspaceErrorCode.WORKSPACE_ACCESS_DENIED));
        WorkspaceMember membership = memberships.findLatestByWorkspaceIdAndMemberIdForUpdate(
                workspaceId,
                memberId
        )
                .orElseThrow(() -> new WorkspaceException(WorkspaceErrorCode.WORKSPACE_ACCESS_DENIED));
        if (!membership.isActive()) {
            throw new WorkspaceException(WorkspaceErrorCode.WORKSPACE_ACCESS_DENIED);
        }
    }

    private SearchQuestionAcceptedResult restore(
            SearchRequestReceipt receipt,
            String content
    ) {
        SearchMessage question = repository.findMessage(receipt.getQuestionMessageId())
                .orElseThrow();
        if (!question.getContent()
                .equals(content)) {
            throw new SearchException(SearchErrorCode.REQUEST_ID_CONFLICT);
        }
        return new SearchQuestionAcceptedResult(
                receipt.getConversationId(),
                receipt.getQuestionMessageId(),
                receipt.getAnswerMessageId(),
                receipt.getId()
        );
    }

    private SearchQuestionAcceptedResult create(
            long workspaceId,
            long memberId,
            SearchQuestionCommand command
    ) {
        Instant acceptedAt = clock.instant();
        SearchConversation conversation = repository.save(
                SearchConversation.create(
                        workspaceId,
                        memberId,
                        acceptedAt
                )
        );
        long conversationId = conversation.getId();
        SearchMessage question = repository.save(
                SearchMessage.receiveQuestion(
                        conversationId,
                        1,
                        command.content(),
                        acceptedAt
                )
        );
        SearchMessage answer = repository.save(
                SearchMessage.startAnswer(
                        conversationId,
                        2,
                        acceptedAt
                )
        );
        SearchRequestReceipt receipt = repository.save(
                SearchRequestReceipt.firstQuestion(
                        workspaceId,
                        memberId,
                        command.requestId(),
                        conversationId,
                        question.getId(),
                        answer.getId(),
                        acceptedAt
                )
        );
        repository.save(
                SearchAnswerExecution.queue(
                        conversationId,
                        question.getId(),
                        answer.getId(),
                        acceptedAt
                )
        );
        repository.flush();
        return new SearchQuestionAcceptedResult(
                conversationId,
                question.getId(),
                answer.getId(),
                receipt.getId()
        );
    }
}
