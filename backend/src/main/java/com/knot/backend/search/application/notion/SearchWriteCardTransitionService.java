package com.knot.backend.search.application.notion;

import com.knot.backend.search.application.dto.result.SearchWriteCardResult;
import com.knot.backend.search.domain.SearchErrorCode;
import com.knot.backend.search.domain.SearchException;
import com.knot.backend.search.domain.model.SearchConversation;
import com.knot.backend.search.domain.model.notion.NotionConnection;
import com.knot.backend.search.domain.model.notion.SearchCreatedNotionPage;
import com.knot.backend.search.domain.model.notion.SearchWriteCard;
import com.knot.backend.search.domain.model.notion.SearchWriteCardFailureReason;
import com.knot.backend.search.domain.model.notion.SearchWriteCardRetryReason;
import com.knot.backend.search.domain.model.notion.SearchWriteCardStatus;
import com.knot.backend.search.domain.repository.SearchRepository;
import com.knot.backend.search.domain.repository.notion.NotionConnectionRepository;
import com.knot.backend.search.domain.repository.notion.SearchWriteCardRepository;
import com.knot.backend.workspace.domain.repository.WorkspaceMemberRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 쓰기 확인 카드의 상태를 카드 행을 잠근 짧은 트랜잭션에서 바꾼다. 노션 호출은 트랜잭션 밖에서 한다(SEARCH-R43, R50).
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class SearchWriteCardTransitionService {

    private final SearchRepository conversations;
    private final WorkspaceMemberRepository memberships;
    private final SearchWriteCardRepository cards;
    private final NotionConnectionRepository connections;
    private final SearchAnswerNotionReader reader;
    private final Clock clock;

    /**
     * 대기인 카드면 연결을 판정해 실행 중으로 바꾼다. abandonedAfter보다 오래 실행 중인 카드는 서버가 실행 중에 멈춘 것으로 보고
     * 무응답 실패로 끝낸다.
     */
    public SearchWriteCardStart begin(
            long workspaceId,
            long memberId,
            long conversationId,
            long cardId,
            Duration abandonedAfter
    ) {
        SearchWriteCard card = lockAccessibleCard(
                workspaceId,
                memberId,
                conversationId,
                cardId
        );
        if (!card.isExecutable()) {
            throw new SearchException(SearchErrorCode.WRITE_CARD_NOT_EXECUTABLE);
        }
        Instant now = clock.instant();
        if (isAbandoned(
                card,
                now.minus(abandonedAfter)
        )) {
            card.fail(
                    SearchWriteCardFailureReason.NOTION_NO_RESPONSE,
                    now
            );
            return settled(card);
        }
        if (card.getStatus() == SearchWriteCardStatus.RUNNING) {
            return new SearchWriteCardStart.Running();
        }
        if (card.getStatus() != SearchWriteCardStatus.PENDING) {
            return settled(card);
        }
        return start(
                card,
                now
        );
    }

    /**
     * abandonedAfter보다 오래 실행 중인 카드를 무응답 실패로 끝낸다. 실행 API의 회수 규칙과 같은 결과다.
     */
    public int failAbandoned(Duration abandonedAfter) {
        Instant now = clock.instant();
        return cards.failAbandonedRunning(
                now.minus(abandonedAfter),
                now
        );
    }

    public SearchWriteCardResult finish(
            long cardId,
            SearchWriteCardOutcome outcome
    ) {
        SearchWriteCard card = cards.findByIdForUpdate(cardId)
                .orElseThrow(() -> new SearchException(SearchErrorCode.INVALID_SEARCH_STATE));
        if (card.getStatus() != SearchWriteCardStatus.RUNNING) {
            log.warn(
                    "Search write card finished after abandoned cardId={} status={} outcome={}",
                    cardId,
                    card.getStatus(),
                    outcome.getClass()
                            .getSimpleName()
            );
            return reader.card(card);
        }
        apply(
                card,
                outcome,
                clock.instant()
        );
        return reader.card(card);
    }

    @Transactional(readOnly = true)
    public SearchWriteCardResult read(long cardId) {
        return reader.card(
                cards.findById(cardId)
                        .orElseThrow(() -> new SearchException(SearchErrorCode.INVALID_SEARCH_STATE))
        );
    }

    /**
     * 대기인 카드를 취소됨으로 바꾼다. 이미 취소됨이면 지금 상태를 준다(SEARCH-R41~R43).
     */
    public SearchWriteCardResult cancel(
            long workspaceId,
            long memberId,
            long conversationId,
            long cardId
    ) {
        SearchWriteCard card = lockAccessibleCard(
                workspaceId,
                memberId,
                conversationId,
                cardId
        );
        card.cancel(clock.instant());
        return reader.card(card);
    }

    private SearchWriteCard lockAccessibleCard(
            long workspaceId,
            long memberId,
            long conversationId,
            long cardId
    ) {
        if (!memberships.existsByWorkspaceIdAndMemberId(
                workspaceId,
                memberId
        )) {
            throw new SearchException(SearchErrorCode.SEARCH_ACCESS_DENIED);
        }
        SearchConversation conversation = conversations.findConversation(conversationId)
                .orElseThrow(() -> new SearchException(SearchErrorCode.WRITE_CARD_NOT_FOUND));
        if (conversation.getWorkspaceId() != workspaceId || conversation.getMemberId() != memberId) {
            throw new SearchException(SearchErrorCode.SEARCH_ACCESS_DENIED);
        }
        SearchWriteCard card = cards.findByIdForUpdate(cardId)
                .filter(found -> found.getConversationId() == conversationId)
                .orElseThrow(() -> new SearchException(SearchErrorCode.WRITE_CARD_NOT_FOUND));
        if (!card.isRequestedBy(memberId)) {
            throw new SearchException(SearchErrorCode.SEARCH_ACCESS_DENIED);
        }
        return card;
    }

    private boolean isAbandoned(
            SearchWriteCard card,
            Instant abandonedBefore
    ) {
        return card.getStatus() == SearchWriteCardStatus.RUNNING && card.getUpdatedAt()
                .isBefore(abandonedBefore);
    }

    /**
     * 연결 없음, 다른 노션으로 바뀜, 연결 만료 순서로 판정하고 해당하면 노션을 부르지 않는다(SEARCH-R52, R53).
     */
    private SearchWriteCardStart start(
            SearchWriteCard card,
            Instant now
    ) {
        Optional<NotionConnection> found = connections.findByWorkspaceId(card.getWorkspaceId());
        card.start(now);
        if (found.isEmpty()) {
            return returnToPending(
                    card,
                    SearchWriteCardRetryReason.NOTION_CONNECTION_REQUIRED,
                    now
            );
        }
        NotionConnection connection = found.get();
        if (!card.isCreatedIn(connection.getNotionWorkspaceId())) {
            card.fail(
                    SearchWriteCardFailureReason.NOTION_CONNECTION_CHANGED,
                    now
            );
            return settled(card);
        }
        if (!connection.isConnected()) {
            return returnToPending(
                    card,
                    SearchWriteCardRetryReason.NOTION_RECONNECT_REQUIRED,
                    now
            );
        }
        return new SearchWriteCardStart.Ready(
                card.getWorkspaceId(),
                new SearchNotionPageCreation(
                        card.getLocationKind(),
                        card.getLocationNotionId(),
                        card.getTitle(),
                        Objects.requireNonNullElse(
                                card.getDatabaseFields(),
                                List.of()
                        ),
                        card.getBody()
                ),
                card.getMeetingDate()
        );
    }

    private SearchWriteCardStart returnToPending(
            SearchWriteCard card,
            SearchWriteCardRetryReason reason,
            Instant now
    ) {
        card.returnToPending(
                reason,
                now
        );
        return settled(card);
    }

    private SearchWriteCardStart settled(SearchWriteCard card) {
        return new SearchWriteCardStart.Settled(reader.card(card));
    }

    private void apply(
            SearchWriteCard card,
            SearchWriteCardOutcome outcome,
            Instant now
    ) {
        switch (outcome) {
            case SearchWriteCardOutcome.Created created -> complete(
                    card,
                    created,
                    now
            );
            case SearchWriteCardOutcome.Retry retry -> card.returnToPending(
                    retry.reason(),
                    now
            );
            case SearchWriteCardOutcome.DatabaseFieldsChanged changed -> card.replaceDatabaseFields(
                    changed.fields(),
                    now
            );
            case SearchWriteCardOutcome.Failed failed -> card.fail(
                    failed.reason(),
                    now
            );
        }
    }

    /**
     * 성공하면 카드와 만든 페이지 이력을 같은 트랜잭션에 저장한다(SEARCH-R50, R51).
     */
    private void complete(
            SearchWriteCard card,
            SearchWriteCardOutcome.Created created,
            Instant now
    ) {
        card.complete(
                created.url(),
                now
        );
        cards.save(
                SearchCreatedNotionPage.record(
                        card,
                        created.pageId(),
                        created.url(),
                        now
                )
        );
    }
}
