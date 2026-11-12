package com.knot.backend.search.application;

import com.knot.backend.document.domain.model.Document;
import com.knot.backend.document.domain.model.DocumentStatus;
import com.knot.backend.document.domain.repository.DocumentRepository;
import com.knot.backend.global.config.SearchWorkerProperties;
import com.knot.backend.search.application.dto.result.SearchAnswerWorkResult;
import com.knot.backend.search.application.dto.result.SearchAnswerStopResult;
import com.knot.backend.search.application.dto.result.SearchDocumentResult;
import com.knot.backend.search.domain.model.SearchAnswerExecution;
import com.knot.backend.search.domain.model.SearchConversation;
import com.knot.backend.search.domain.SearchErrorCode;
import com.knot.backend.search.domain.SearchException;
import com.knot.backend.search.domain.model.SearchEvidence;
import com.knot.backend.search.domain.model.SearchMessage;
import com.knot.backend.search.domain.model.SearchMessageStatus;
import com.knot.backend.search.domain.model.SearchMessageRole;
import com.knot.backend.search.domain.model.SearchExecutionState;
import com.knot.backend.search.domain.SearchException;
import com.knot.backend.search.domain.SearchErrorCode;
import com.knot.backend.search.domain.repository.SearchRepository;
import com.knot.backend.workspace.domain.repository.WorkspaceRepository;
import com.knot.backend.workspace.domain.repository.WorkspaceMemberRepository;
import java.time.Clock;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@lombok.extern.slf4j.Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class SearchAnswerExecutionService {

    private final SearchRepository repository;
    private final WorkspaceRepository workspaces;
    private final WorkspaceMemberRepository memberships;
    private final DocumentRepository documents;
    private final SearchWorkerProperties properties;
    private final Clock clock;

    public SearchAnswerStopResult stop(
            long workspaceId,
            long memberId,
            long conversationId,
            long answerId
    ) {
        SearchConversation conversation = repository.findConversation(conversationId)
                .orElseThrow(() -> new SearchException(SearchErrorCode.ANSWER_NOT_FOUND));
        validateStopAccess(
                workspaceId,
                memberId,
                conversation
        );
        workspaces.findIncludingDeletedByIdForUpdate(workspaceId)
                .orElseThrow(() -> new SearchException(SearchErrorCode.SEARCH_ACCESS_DENIED));
        SearchConversation locked = repository.findConversationForUpdate(conversationId)
                .orElseThrow(() -> new SearchException(SearchErrorCode.ANSWER_NOT_FOUND));
        validateStopAccess(
                workspaceId,
                memberId,
                locked
        );
        SearchMessage answer = repository.findMessage(answerId)
                .orElseThrow(() -> new SearchException(SearchErrorCode.ANSWER_NOT_FOUND));
        validateStopAnswer(
                answer,
                conversationId
        );
        SearchAnswerExecution execution = repository.findExecutionForUpdate(answerId)
                .orElseThrow(() -> new SearchException(SearchErrorCode.ANSWER_GENERATION_UNAVAILABLE));
        validateStopExecution(
                execution,
                answer
        );
        if (answer.getStatus() == SearchMessageStatus.STOPPED) {
            return stoppedResult(answer);
        }
        if (answer.getStatus() != SearchMessageStatus.STREAMING) {
            throw new SearchException(SearchErrorCode.ANSWER_NOT_RUNNING);
        }
        if (execution.getState() == SearchExecutionState.FINISHED) {
            throw new SearchException(SearchErrorCode.ANSWER_GENERATION_UNAVAILABLE);
        }
        answer.stop();
        execution.stop();
        recordTerminalActivity(
                locked,
                execution,
                SearchMessageStatus.STOPPED
        );
        repository.flush();
        return stoppedResult(answer);
    }

    private SearchAnswerStopResult stoppedResult(SearchMessage answer) {
        return new SearchAnswerStopResult(
                answer.getId(),
                answer.getStatus(),
                answer.getContent()
        );
    }

    private void validateStopAccess(
            long workspaceId,
            long memberId,
            SearchConversation conversation
    ) {
        if (conversation.getWorkspaceId() != workspaceId) {
            throw new SearchException(SearchErrorCode.SEARCH_ACCESS_DENIED);
        }
        if (conversation.getMemberId() != memberId) {
            throw new SearchException(SearchErrorCode.SEARCH_ACCESS_DENIED);
        }
        if (!memberships.existsByWorkspaceIdAndMemberId(
                workspaceId,
                memberId
        )) {
            throw new SearchException(SearchErrorCode.SEARCH_ACCESS_DENIED);
        }
    }

    private void validateStopAnswer(
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

    private void validateStopExecution(
            SearchAnswerExecution execution,
            SearchMessage answer
    ) {
        if (execution.getConversationId() != answer.getConversationId()) {
            throw new SearchException(SearchErrorCode.ANSWER_GENERATION_UNAVAILABLE);
        }
        SearchMessage question = repository.findMessage(execution.getQuestionId())
                .orElseThrow(() -> new SearchException(SearchErrorCode.ANSWER_GENERATION_UNAVAILABLE));
        if (question.getConversationId() != answer.getConversationId()) {
            throw new SearchException(SearchErrorCode.ANSWER_GENERATION_UNAVAILABLE);
        }
        if (question.getRole() != SearchMessageRole.USER || question.getSequence() + 1 != answer.getSequence()) {
            throw new SearchException(SearchErrorCode.ANSWER_GENERATION_UNAVAILABLE);
        }
    }

    public Optional<SearchAnswerWorkResult> claim(long answerId) {
        Optional<SearchAnswerExecution> locked = lockExecution(answerId);
        if (locked.isEmpty()) {
            return Optional.empty();
        }
        SearchAnswerExecution execution = locked.get();
        UUID token = UUID.randomUUID();
        if (!execution.claim(
                token,
                clock.instant(),
                properties.getExecutionLease()
        )) {
            return Optional.empty();
        }
        SearchConversation conversation = conversation(execution);
        if (!hasAccess(conversation)) {
            finishFailure(
                    execution,
                    "SEARCH_ACCESS_DENIED"
            );
            return Optional.empty();
        }
        SearchMessage question = repository.findMessage(execution.getQuestionId())
                .orElseThrow();
        return Optional.of(
                new SearchAnswerWorkResult(
                        conversation.getWorkspaceId(),
                        conversation.getMemberId(),
                        conversation.getId(),
                        question.getId(),
                        answerId,
                        token,
                        question.getContent(),
                        execution.getCreatedAt()
                )
        );
    }

    public boolean isCurrent(
            long answerId,
            UUID token
    ) {
        return currentExecution(
                answerId,
                token
        ).isPresent();
    }

    public boolean recordRetrieval(
            long answerId,
            UUID token,
            int excludedCount,
            List<SearchDocumentResult> candidates
    ) {
        Optional<SearchAnswerExecution> current = currentExecution(
                answerId,
                token
        );
        if (current.isEmpty()) {
            return false;
        }
        SearchAnswerExecution execution = current.get();
        long workspaceId = conversation(execution).getWorkspaceId();
        Set<Long> ids = new HashSet<>();
        for (SearchDocumentResult candidate : candidates) {
            if (!ids.add(candidate.documentId())) {
                throw new SearchException(SearchErrorCode.INVALID_SEARCH_STATE);
            }
            requireDocument(
                    workspaceId,
                    candidate.documentId()
            );
        }
        execution.recordRetrieval(excludedCount);
        return true;
    }

    public boolean append(
            long answerId,
            UUID token,
            String text
    ) {
        Optional<SearchAnswerExecution> current = currentExecution(
                answerId,
                token
        );
        if (current.isEmpty()) {
            return false;
        }
        SearchAnswerExecution execution = current.get();
        answer(execution).appendContent(text);
        conversation(execution).recordMessageActivity(clock.instant());
        return true;
    }

    public boolean completeGenerated(
            long answerId,
            UUID token,
            String content,
            List<Long> usedDocumentIds,
            Set<Long> candidateIds
    ) {
        // Publish validated text, evidence and terminal state in the same transaction.
        Optional<SearchAnswerExecution> current = currentExecution(
                answerId,
                token
        );
        if (current.isEmpty()) {
            return false;
        }
        answer(current.get()).appendContent(content);
        return completeCurrent(
                current.get(),
                usedDocumentIds,
                candidateIds
        );
    }

    public boolean complete(
            long answerId,
            UUID token,
            List<Long> usedDocumentIds,
            Set<Long> candidateIds
    ) {
        Optional<SearchAnswerExecution> current = currentExecution(
                answerId,
                token
        );
        if (current.isEmpty()) {
            return false;
        }
        return completeCurrent(
                current.get(),
                usedDocumentIds,
                candidateIds
        );
    }

    private boolean completeCurrent(
            SearchAnswerExecution execution,
            List<Long> usedDocumentIds,
            Set<Long> candidateIds
    ) {
        validateUsedDocuments(
                usedDocumentIds,
                candidateIds
        );
        long answerId = execution.getAnswerId();
        SearchConversation conversation = conversation(execution);
        for (int index = 0; index < usedDocumentIds.size(); index++) {
            long documentId = usedDocumentIds.get(index);
            requireDocument(
                    conversation.getWorkspaceId(),
                    documentId
            );
            repository.save(
                    SearchEvidence.create(
                            answerId,
                            documentId,
                            index + 1
                    )
            );
        }
        answer(execution).complete();
        execution.finish();
        recordTerminalActivity(
                conversation,
                execution,
                SearchMessageStatus.COMPLETED
        );
        repository.flush();
        return true;
    }

    public boolean fail(
            long answerId,
            UUID token,
            String code
    ) {
        Optional<SearchAnswerExecution> current = currentExecution(
                answerId,
                token
        );
        if (current.isEmpty()) {
            return false;
        }
        finishFailure(
                current.get(),
                code
        );
        return true;
    }

    public boolean expire(long answerId) {
        Optional<SearchAnswerExecution> locked = lockExecution(answerId);
        if (locked.isEmpty()) {
            return false;
        }
        SearchAnswerExecution execution = locked.get();
        if (!execution.isExpired(clock.instant())) {
            return false;
        }
        finishFailure(
                execution,
                "ANSWER_GENERATION_TIMEOUT"
        );
        return true;
    }

    private Optional<SearchAnswerExecution> lockExecution(long answerId) {
        Optional<SearchAnswerExecution> found = repository.findExecution(answerId);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        SearchConversation conversation = conversation(found.get());
        workspaces.findIncludingDeletedByIdForUpdate(conversation.getWorkspaceId())
                .orElseThrow();
        repository.findConversationForUpdate(conversation.getId())
                .orElseThrow();
        return repository.findExecutionForUpdate(answerId);
    }

    private Optional<SearchAnswerExecution> currentExecution(
            long answerId,
            UUID token
    ) {
        Optional<SearchAnswerExecution> locked = lockExecution(answerId);
        if (locked.isEmpty()) {
            return Optional.empty();
        }
        SearchAnswerExecution execution = locked.get();
        if (!execution.isCurrent(
                token,
                clock.instant()
        )) {
            return Optional.empty();
        }
        if (!hasAccess(conversation(execution))) {
            finishFailure(
                    execution,
                    "SEARCH_ACCESS_DENIED"
            );
            return Optional.empty();
        }
        return locked;
    }

    private boolean hasAccess(SearchConversation conversation) {
        return memberships.existsByWorkspaceIdAndMemberId(
                conversation.getWorkspaceId(),
                conversation.getMemberId()
        );
    }

    private SearchConversation conversation(SearchAnswerExecution execution) {
        return repository.findConversation(execution.getConversationId())
                .orElseThrow();
    }

    private SearchMessage answer(SearchAnswerExecution execution) {
        return repository.findMessage(execution.getAnswerId())
                .orElseThrow();
    }

    private void requireDocument(
            long workspaceId,
            long documentId
    ) {
        Document document = documents.findByWorkspaceIdAndIdForUpdate(
                workspaceId,
                documentId
        )
                .orElseThrow(() -> new SearchException(SearchErrorCode.INVALID_SEARCH_STATE));
        if (document.getStatus() == DocumentStatus.DRAFT) {
            return;
        }
        if (document.getStatus() == DocumentStatus.ARCHIVED) {
            return;
        }
        throw new SearchException(SearchErrorCode.INVALID_SEARCH_STATE);
    }

    private void validateUsedDocuments(
            List<Long> ids,
            Set<Long> candidates
    ) {
        if (ids == null) {
            throw new SearchException(SearchErrorCode.INVALID_SEARCH_STATE);
        }
        if (new HashSet<>(ids).size() != ids.size()) {
            throw new SearchException(SearchErrorCode.INVALID_SEARCH_STATE);
        }
        if (!candidates.containsAll(ids)) {
            throw new SearchException(SearchErrorCode.INVALID_SEARCH_STATE);
        }
    }

    private void finishFailure(
            SearchAnswerExecution execution,
            String code
    ) {
        if ("SEARCH_ACCESS_DENIED".equals(code) || !hasAccess(conversation(execution))) {
            answer(execution).fail("SEARCH_ACCESS_DENIED");
            finishTerminal(
                    execution,
                    code,
                    "access_denied",
                    SearchMessageStatus.FAILED
            );
            return;
        }
        answer(execution).completeGuidance(SearchAnswerGuidance.UNAVAILABLE);
        finishTerminal(
                execution,
                code,
                "guidance",
                SearchMessageStatus.COMPLETED
        );
    }

    private void finishTerminal(
            SearchAnswerExecution execution,
            String code,
            String outcome,
            SearchMessageStatus status
    ) {
        log.info(
                "Search answer terminal answerId={} outcome={} failureCode={}",
                execution.getAnswerId(),
                outcome,
                code
        );
        execution.finish();
        recordTerminalActivity(
                conversation(execution),
                execution,
                status
        );
    }

    private void recordTerminalActivity(
            SearchConversation conversation,
            SearchAnswerExecution execution,
            SearchMessageStatus status
    ) {
        SearchMessage question = repository.findMessage(execution.getQuestionId())
                .orElseThrow();
        if (question.getSequence() == 1) {
            conversation.recordFirstAnswerStatus(status);
        }
        conversation.recordMessageActivity(clock.instant());
    }
}
