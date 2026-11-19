package com.knot.backend.search.application.notion;

import com.knot.backend.global.config.SearchNotionProperties;
import com.knot.backend.search.application.dto.result.SearchUsePlan;
import com.knot.backend.search.domain.model.notion.NotionConnection;
import com.knot.backend.search.domain.model.notion.SearchNotionNoticeReason;
import com.knot.backend.search.domain.repository.notion.NotionConnectionRepository;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * 계획에 있는 노션 읽기와 만들 위치 검색을 서버가 실행한다. 노션 본문은 답변 AI에 넘길 뿐 저장하지 않는다(SEARCH-R36).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SearchNotionReader {

    private final ObjectProvider<SearchNotionClient> clients;
    private final NotionConnectionRepository connections;
    private final SearchNotionProperties properties;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    @PreDestroy
    void close() {
        executor.shutdownNow();
    }

    /**
     * 연결이 없거나 만료면 노션을 부르지 않는다. 나머지는 기다리지 않고 시작해 회의 문서 찾기와 시간을 겹친다.
     */
    public SearchNotionReading start(
            long workspaceId,
            SearchUsePlan plan
    ) {
        Optional<NotionConnection> connection = connections.findByWorkspaceId(workspaceId);
        Optional<SearchNotionNoticeReason> blocked = blocked(connection);
        return new SearchNotionReading(
                plan.notionReadQuery()
                        .map(
                                query -> read(
                                        workspaceId,
                                        query,
                                        blocked
                                )
                        ),
                plan.notionCreateLocation()
                        .map(
                                location -> locate(
                                        workspaceId,
                                        location,
                                        connection,
                                        blocked
                                )
                        )
        );
    }

    private static Optional<SearchNotionNoticeReason> blocked(Optional<NotionConnection> connection) {
        if (connection.isEmpty()) {
            return Optional.of(SearchNotionNoticeReason.NOT_CONNECTED);
        }
        if (!connection.get()
                .isConnected()) {
            return Optional.of(SearchNotionNoticeReason.CONNECTION_EXPIRED);
        }
        return Optional.empty();
    }

    private CompletableFuture<SearchNotionReadResult> read(
            long workspaceId,
            String query,
            Optional<SearchNotionNoticeReason> blocked
    ) {
        if (blocked.isPresent()) {
            return CompletableFuture.completedFuture(SearchNotionReadResult.failed(blocked.get()));
        }
        // shortcut: 페이지 하나라도 실패하거나 시간 한도를 넘으면 읽은 페이지도 모두 버린다. 일부만 쓰자는 요구가 생기면 바꾼다.
        return CompletableFuture.supplyAsync(
                () -> readPages(
                        workspaceId,
                        query
                ),
                executor
        )
                .exceptionally(
                        error -> SearchNotionReadResult.failed(
                                readFailure(
                                        workspaceId,
                                        error
                                )
                        )
                )
                .completeOnTimeout(
                        SearchNotionReadResult.failed(SearchNotionNoticeReason.READ_FAILED),
                        properties.getReadTimeout()
                                .toMillis(),
                        TimeUnit.MILLISECONDS
                );
    }

    private SearchNotionReadResult readPages(
            long workspaceId,
            String query
    ) {
        SearchNotionClient client = clients.getObject();
        Duration timeout = properties.getReadTimeout();
        List<SearchNotionSearchHit> hits = client.search(
                workspaceId,
                query,
                timeout
        )
                .stream()
                .filter(hit -> hit.kind() == SearchNotionObjectKind.PAGE)
                .filter(
                        hit -> !hit.url()
                                .isBlank()
                )
                .toList();
        if (hits.isEmpty()) {
            return SearchNotionReadResult.failed(SearchNotionNoticeReason.PAGE_NOT_FOUND);
        }
        List<CompletableFuture<SearchNotionPage>> pages = hits.stream()
                .map(
                        hit -> CompletableFuture.supplyAsync(
                                () -> page(
                                        client,
                                        workspaceId,
                                        hit,
                                        query
                                ),
                                executor
                        )
                )
                .toList();
        return SearchNotionReadResult.read(
                pages.stream()
                        .map(CompletableFuture::join)
                        .toList()
        );
    }

    private SearchNotionPage page(
            SearchNotionClient client,
            long workspaceId,
            SearchNotionSearchHit hit,
            String query
    ) {
        SearchNotionPage fetched = client.fetchPage(
                workspaceId,
                hit.id(),
                properties.getReadTimeout()
        );
        List<String> anchors = new ArrayList<>();
        anchors.add(hit.highlight());
        anchors.add(query);
        anchors.addAll(List.of(query.split("\\s+")));
        String title = hit.title();
        if (title == null) {
            title = "";
        }
        return new SearchNotionPage(
                hit.id(),
                title,
                hit.url(),
                excerpt(
                        fetched.content(),
                        anchors,
                        properties.getPageContentCharacterLimit()
                )
        );
    }

    /**
     * 정해 둔 길이까지는 전체를 넘기고, 넘으면 앞에서 자르지 않고 찾는 낱말이 처음 나온 곳 근처를 그 길이만큼 넘긴다. 낱말을 찾지 못하면
     * 앞에서부터 자른다.
     */
    static String excerpt(
            String content,
            List<String> anchors,
            int limit
    ) {
        if (content.length() <= limit) {
            return content;
        }
        int found = anchors.stream()
                .filter(anchor -> anchor != null && !anchor.isBlank())
                .mapToInt(content::indexOf)
                .filter(index -> index >= 0)
                .findFirst()
                .orElse(0);
        int start = Math.clamp(
                found - limit / 2,
                0,
                content.length() - limit
        );
        int end = start + limit;
        if (Character.isLowSurrogate(content.charAt(start))) {
            start++;
        }
        if (Character.isHighSurrogate(content.charAt(end - 1))) {
            end--;
        }
        return content.substring(
                start,
                end
        );
    }

    private CompletableFuture<SearchNotionWritePlan> locate(
            long workspaceId,
            String location,
            Optional<NotionConnection> connection,
            Optional<SearchNotionNoticeReason> blocked
    ) {
        if (blocked.isPresent()) {
            return CompletableFuture
                    .completedFuture(SearchNotionWritePlan.skipped(SearchNotionWriteSkip.from(blocked.get())));
        }
        if (location.isBlank()) {
            return CompletableFuture
                    .completedFuture(SearchNotionWritePlan.skipped(SearchNotionWriteSkip.LOCATION_MISSING));
        }
        String notionWorkspaceId = connection.orElseThrow()
                .getNotionWorkspaceId();
        return CompletableFuture.supplyAsync(
                () -> findLocation(
                        workspaceId,
                        location,
                        notionWorkspaceId
                ),
                executor
        )
                .exceptionally(
                        error -> SearchNotionWritePlan.skipped(
                                locationFailure(
                                        workspaceId,
                                        error
                                )
                        )
                )
                .completeOnTimeout(
                        SearchNotionWritePlan.skipped(SearchNotionWriteSkip.LOCATION_FAILED),
                        properties.getLocationTimeout()
                                .toMillis(),
                        TimeUnit.MILLISECONDS
                );
    }

    /**
     * 노션이 준 순서(관련도)의 맨 앞 후보를 위치로 정하고 되묻지 않는다(SEARCH-R45, 정렬 기준은 정본 미정).
     */
    private SearchNotionWritePlan findLocation(
            long workspaceId,
            String location,
            String notionWorkspaceId
    ) {
        SearchNotionClient client = clients.getObject();
        List<SearchNotionSearchHit> hits = client.search(
                workspaceId,
                location,
                properties.getLocationTimeout()
        );
        if (hits.isEmpty()) {
            return SearchNotionWritePlan.skipped(SearchNotionWriteSkip.LOCATION_NOT_FOUND);
        }
        SearchNotionSearchHit hit = hits.getFirst();
        if (hit.kind() == SearchNotionObjectKind.DATABASE) {
            return SearchNotionWritePlan.ready(
                    database(
                            client,
                            workspaceId,
                            notionWorkspaceId,
                            hit
                    )
            );
        }
        return SearchNotionWritePlan.ready(
                SearchNotionLocation.page(
                        notionWorkspaceId,
                        hit
                )
        );
    }

    private SearchNotionLocation database(
            SearchNotionClient client,
            long workspaceId,
            String notionWorkspaceId,
            SearchNotionSearchHit hit
    ) {
        try {
            return SearchNotionLocation.database(
                    notionWorkspaceId,
                    hit,
                    client.fetchDatabase(
                            workspaceId,
                            hit.id(),
                            properties.getLocationTimeout()
                    )
            );
        } catch (RuntimeException exception) {
            // DB 속성을 확인하지 못한 카드는 만들되 실행 요청을 받지 않는다(SEARCH-R49).
            log.warn(
                    "Search notion phase=database_structure workspaceId={} outcome=failure failure={}",
                    workspaceId,
                    failureName(exception)
            );
            return SearchNotionLocation.unknownDatabase(
                    notionWorkspaceId,
                    hit
            );
        }
    }

    private SearchNotionNoticeReason readFailure(
            long workspaceId,
            Throwable error
    ) {
        log.warn(
                "Search notion phase=read workspaceId={} outcome=failure failure={}",
                workspaceId,
                failureName(error)
        );
        return failure(error).flatMap(SearchNotionReader::connectionReason)
                .orElse(SearchNotionNoticeReason.READ_FAILED);
    }

    private SearchNotionWriteSkip locationFailure(
            long workspaceId,
            Throwable error
    ) {
        log.warn(
                "Search notion phase=location workspaceId={} outcome=failure failure={}",
                workspaceId,
                failureName(error)
        );
        return failure(error).flatMap(SearchNotionReader::connectionReason)
                .map(SearchNotionWriteSkip::from)
                .orElse(SearchNotionWriteSkip.LOCATION_FAILED);
    }

    private static Optional<SearchNotionNoticeReason> connectionReason(SearchNotionFailure failure) {
        return switch (failure) {
            case NOT_CONNECTED -> Optional.of(SearchNotionNoticeReason.NOT_CONNECTED);
            case CONNECTION_EXPIRED -> Optional.of(SearchNotionNoticeReason.CONNECTION_EXPIRED);
            default -> Optional.empty();
        };
    }

    private static Optional<SearchNotionFailure> failure(Throwable error) {
        Throwable cause = error;
        while (cause instanceof CompletionException && cause.getCause() != null) {
            cause = cause.getCause();
        }
        if (cause instanceof SearchNotionException notion) {
            return Optional.of(notion.failure());
        }
        return Optional.empty();
    }

    private static String failureName(Throwable error) {
        return failure(error).map(Enum::name)
                .orElse(
                        error.getClass()
                                .getSimpleName()
                );
    }
}
