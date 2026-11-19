package com.knot.backend.search.infrastructure.persistence;

import com.knot.backend.search.application.SearchMessageListQuery;
import com.knot.backend.search.application.SearchAnswerContentPolicy.EvidenceStatus;
import com.knot.backend.search.domain.SearchErrorCode;
import com.knot.backend.search.domain.SearchException;
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
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.RecoverableDataAccessException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.TransactionTimedOutException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;

@Slf4j
@Repository
@RequiredArgsConstructor
public class SearchMessageListQueryAdapter implements SearchMessageListQuery {

    private final SearchMessageListJpaRepository messages;
    private final SearchEvidenceReadJpaRepository evidences;
    private final SearchDocumentListStore documentLists;
    private final SearchEvidenceHistoryStore history;

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
    public Map<Long, EvidenceStatus> findEvidenceStatuses(
            long workspaceId,
            long memberId,
            long conversationId,
            List<Long> answerIds,
            boolean compareVersions
    ) {
        if (answerIds.isEmpty()) {
            return Map.of();
        }
        List<SearchEvidenceHistoryStore.Access> rows;
        try {
            rows = history.access(
                    workspaceId,
                    memberId,
                    conversationId,
                    answerIds
            );
        } catch (DataAccessException | TransactionException failure) {
            log.warn(
                    "Search evidence access unavailable conversationId={}",
                    conversationId
            );
            return hidden(answerIds);
        }
        Map<Long, EvidenceStatus> statuses = accessStatuses(
                answerIds,
                rows
        );
        if (!compareVersions) {
            return statuses;
        }
        List<Long> comparable = rows.stream()
                .filter(row -> !row.hidden() && row.hasSources())
                .map(SearchEvidenceHistoryStore.Access::answerId)
                .toList();
        if (comparable.isEmpty()) {
            return statuses;
        }
        Map<Long, EvidenceStatus> compared = compare(
                workspaceId,
                memberId,
                conversationId,
                comparable
        );
        try {
            rows = history.recheckAccess(
                    workspaceId,
                    memberId,
                    conversationId,
                    comparable
            );
        } catch (DataAccessException | TransactionException failure) {
            log.warn(
                    "Search evidence access recheck unavailable conversationId={}",
                    conversationId
            );
            statuses.putAll(hidden(answerIds));
            return statuses;
        }
        Map<Long, EvidenceStatus> current = accessStatuses(
                comparable,
                rows
        );
        for (long answerId : comparable) {
            EvidenceStatus status = current.get(answerId);
            if (status == EvidenceStatus.AVAILABLE) {
                status = compared.getOrDefault(
                        answerId,
                        EvidenceStatus.CHANGE_UNKNOWN
                );
            }
            statuses.put(
                    answerId,
                    status
            );
        }
        return statuses;
    }

    private Map<Long, EvidenceStatus> accessStatuses(
            List<Long> answerIds,
            List<SearchEvidenceHistoryStore.Access> rows
    ) {
        if (rows.isEmpty()) {
            throw new SearchException(SearchErrorCode.SEARCH_ACCESS_DENIED);
        }
        Map<Long, EvidenceStatus> statuses = hidden(answerIds);
        for (SearchEvidenceHistoryStore.Access row : rows) {
            if (!row.hidden()) {
                statuses.put(
                        row.answerId(),
                        EvidenceStatus.AVAILABLE
                );
            }
        }
        return statuses;
    }

    private Map<Long, EvidenceStatus> hidden(List<Long> answerIds) {
        Map<Long, EvidenceStatus> statuses = new HashMap<>();
        for (long answerId : answerIds) {
            statuses.put(
                    answerId,
                    EvidenceStatus.SOURCE_UNAVAILABLE
            );
        }
        return statuses;
    }

    private Map<Long, EvidenceStatus> compare(
            long workspaceId,
            long memberId,
            long conversationId,
            List<Long> answerIds
    ) {
        try {
            return history.compare(
                    workspaceId,
                    memberId,
                    conversationId,
                    answerIds
            );
        } catch (TransientDataAccessException | RecoverableDataAccessException | TransactionTimedOutException failure) {
            try {
                return history.compare(
                        workspaceId,
                        memberId,
                        conversationId,
                        answerIds
                );
            } catch (DataAccessException | TransactionException retryFailure) {
                log.warn(
                        "Search evidence comparison retry failed conversationId={}",
                        conversationId
                );
            }
        } catch (DataAccessException | TransactionException failure) {
            log.warn(
                    "Search evidence comparison unavailable conversationId={}",
                    conversationId
            );
        }
        return Map.of();
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
