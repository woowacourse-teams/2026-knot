package com.knot.backend.search.application.notion;

import com.knot.backend.global.config.SearchNotionProperties;
import com.knot.backend.search.application.dto.result.SearchWriteCardResult;
import com.knot.backend.search.domain.SearchErrorCode;
import com.knot.backend.search.domain.SearchException;
import com.knot.backend.search.domain.model.notion.SearchWriteCardDatabaseField;
import com.knot.backend.search.domain.model.notion.SearchWriteCardDatabaseFields;
import com.knot.backend.search.domain.model.notion.SearchWriteCardFailureReason;
import com.knot.backend.search.domain.model.notion.SearchWriteCardLocationKind;
import com.knot.backend.search.domain.model.notion.SearchWriteCardStatus;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * 쓰기 확인 카드 실행. 노션에는 실행 요청을 받았을 때만, 카드 하나에 한 번만 쓴다(SEARCH-R40, R43, R50).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SearchWriteCardExecutionService {
    private static final Duration WAIT_MARGIN = Duration.ofSeconds(5);
    private static final Duration POLL_INTERVAL = Duration.ofMillis(200);

    private final SearchWriteCardTransitionService transitions;
    private final ObjectProvider<SearchNotionClient> clients;
    private final SearchNotionProperties properties;

    public SearchWriteCardResult execute(
            long workspaceId,
            long memberId,
            long conversationId,
            long cardId
    ) {
        SearchNotionClient client = clients.getIfAvailable();
        if (client == null) {
            throw new SearchException(SearchErrorCode.INVALID_SEARCH_CONFIGURATION);
        }
        Duration wait = runningWait();
        SearchWriteCardStart start = transitions.begin(
                workspaceId,
                memberId,
                conversationId,
                cardId,
                properties.runningCardAbandonedAfter()
        );
        return switch (start) {
            case SearchWriteCardStart.Ready ready -> transitions.finish(
                    cardId,
                    create(
                            client,
                            ready
                    )
            );
            case SearchWriteCardStart.Running running -> awaitSettled(
                    cardId,
                    wait
            );
            case SearchWriteCardStart.Settled settled -> settled.card();
        };
    }

    /**
     * 실행 중인 카드에 온 요청이 끝 상태나 대기를 기다리는 최대 시간. 만들기 한도와 요청 많음 대기 한도에 여유를 더한다.
     */
    private Duration runningWait() {
        return properties.getCreateTimeout()
                .plus(properties.getRateLimitMaxWait())
                .plus(WAIT_MARGIN);
    }

    private SearchWriteCardOutcome create(
            SearchNotionClient client,
            SearchWriteCardStart.Ready ready
    ) {
        try {
            return changedDatabaseFields(
                    client,
                    ready
            ).orElseGet(
                    () -> createPage(
                            client,
                            ready
                    )
            );
        } catch (SearchNotionException exception) {
            return SearchWriteCardOutcome.from(exception.failure());
        } catch (RuntimeException exception) {
            log.warn(
                    "Search write card notion call failed exception={}",
                    exception.getClass()
                            .getSimpleName()
            );
            return new SearchWriteCardOutcome.Failed(SearchWriteCardFailureReason.NOTION_NO_RESPONSE);
        }
    }

    /**
     * DB 아래에 만들 때 지금 DB 구조로 다시 계산한 칸이 카드의 칸과 다르면 만들지 않는다(SEARCH-R49, R50).
     */
    private Optional<SearchWriteCardOutcome> changedDatabaseFields(
            SearchNotionClient client,
            SearchWriteCardStart.Ready ready
    ) {
        SearchNotionPageCreation creation = ready.creation();
        if (creation.parentKind() != SearchWriteCardLocationKind.DATABASE) {
            return Optional.empty();
        }
        SearchNotionDatabase database = requestWithRetry(
                () -> client.fetchDatabase(
                        ready.workspaceId(),
                        creation.parentId(),
                        properties.getReadTimeout()
                )
        );
        List<SearchWriteCardDatabaseField> fields = SearchWriteCardDatabaseFields.from(
                database.properties(),
                creation.title(),
                ready.meetingDate()
        );
        if (fields.equals(creation.databaseFields())) {
            return Optional.empty();
        }
        return Optional.of(new SearchWriteCardOutcome.DatabaseFieldsChanged(fields));
    }

    private SearchWriteCardOutcome createPage(
            SearchNotionClient client,
            SearchWriteCardStart.Ready ready
    ) {
        SearchNotionCreatedPage page = requestWithRetry(
                () -> client.createPage(
                        ready.workspaceId(),
                        ready.creation(),
                        properties.getCreateTimeout()
                )
        );
        return new SearchWriteCardOutcome.Created(
                page.id(),
                page.url()
        );
    }

    /**
     * 노션이 요청이 많다고 거절하고 알려 준 대기 시간이 한도 이하면 그만큼 기다려 한 번만 다시 요청한다(SEARCH-R52).
     */
    private <T> T requestWithRetry(Supplier<T> request) {
        try {
            return request.get();
        } catch (SearchNotionException exception) {
            awaitRetry(exception);
            return request.get();
        }
    }

    private void awaitRetry(SearchNotionException exception) {
        Duration wait = retryWait(exception);
        try {
            TimeUnit.MILLISECONDS.sleep(wait.toMillis());
        } catch (InterruptedException interrupted) {
            Thread.currentThread()
                    .interrupt();
            throw exception;
        }
    }

    private Duration retryWait(SearchNotionException exception) {
        if (exception.failure() != SearchNotionFailure.RATE_LIMITED) {
            throw exception;
        }
        Duration wait = exception.retryAfter()
                .orElseThrow(() -> exception);
        if (wait.compareTo(properties.getRateLimitMaxWait()) > 0) {
            throw exception;
        }
        return wait;
    }

    /**
     * 다른 요청이 실행 중인 카드는 노션을 부르지 않고 DB를 다시 읽어 기다린다. 시간이 지나도 실행 중이면 RUNNING을 준다.
     */
    private SearchWriteCardResult awaitSettled(
            long cardId,
            Duration wait
    ) {
        long deadline = System.nanoTime() + wait.toNanos();
        SearchWriteCardResult card = transitions.read(cardId);
        while (card.status() == SearchWriteCardStatus.RUNNING && System.nanoTime() < deadline) {
            if (!pause()) {
                return card;
            }
            card = transitions.read(cardId);
        }
        return card;
    }

    private boolean pause() {
        try {
            TimeUnit.MILLISECONDS.sleep(POLL_INTERVAL.toMillis());
            return true;
        } catch (InterruptedException interrupted) {
            Thread.currentThread()
                    .interrupt();
            return false;
        }
    }
}
