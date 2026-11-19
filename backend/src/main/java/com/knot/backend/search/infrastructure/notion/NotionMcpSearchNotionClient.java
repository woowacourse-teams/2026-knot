package com.knot.backend.search.infrastructure.notion;

import com.knot.backend.global.config.SearchNotionProperties;
import com.knot.backend.search.application.notion.SearchNotionClient;
import com.knot.backend.search.application.notion.SearchNotionCreatedPage;
import com.knot.backend.search.application.notion.SearchNotionDatabase;
import com.knot.backend.search.application.notion.SearchNotionException;
import com.knot.backend.search.application.notion.SearchNotionFailure;
import com.knot.backend.search.application.notion.SearchNotionPage;
import com.knot.backend.search.application.notion.SearchNotionPageCreation;
import com.knot.backend.search.application.notion.SearchNotionSearchHit;
import com.knot.backend.search.application.notion.SearchNotionTokenCipher;
import com.knot.backend.search.domain.model.notion.NotionConnection;
import com.knot.backend.search.domain.model.notion.SearchWriteCardDatabaseField;
import com.knot.backend.search.domain.model.notion.SearchWriteCardDatabaseFields;
import com.knot.backend.search.domain.model.notion.SearchWriteCardLocationKind;
import com.knot.backend.search.domain.repository.notion.NotionConnectionRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * Workspace의 노션 연결 토큰으로 노션 MCP 도구를 부른다. 연결이 만료면 노션을 부르지 않는다. access token이 만료
 * 시각을 지났거나 HTTP 401이면 한 번만 갱신하고 다시 부른다.
 */
@Component
@RequiredArgsConstructor
class NotionMcpSearchNotionClient implements SearchNotionClient {
    private static final Set<String> RESERVED_PROPERTY_NAMES = Set.of(
            "id",
            "url"
    );

    private final NotionConnectionRepository connections;
    private final NotionTokenRefresher refresher;
    private final NotionMcpTransport transport;
    private final NotionToolResults results;
    private final SearchNotionTokenCipher cipher;
    private final SearchNotionProperties properties;
    private final Clock clock;

    @Override
    public List<SearchNotionSearchHit> search(
            long workspaceId,
            String query,
            Duration timeout
    ) {
        return call(
                workspaceId,
                timeout,
                (
                        token,
                        deadline
                ) -> results.searchHits(
                        tool(
                                token,
                                "notion-search",
                                Map.of(
                                        "query",
                                        query,
                                        "page_size",
                                        properties.getSearchPageSize()
                                ),
                                deadline,
                                NotionToolAccess.SEARCH
                        )
                )
        );
    }

    @Override
    public SearchNotionPage fetchPage(
            long workspaceId,
            String pageId,
            Duration timeout
    ) {
        return call(
                workspaceId,
                timeout,
                (
                        token,
                        deadline
                ) -> results.page(
                        pageId,
                        fetch(
                                token,
                                pageId,
                                deadline
                        )
                )
        );
    }

    /**
     * DB를 fetch해 첫 데이터 소스(collection://)를 찾고 데이터 소스 스키마를 읽는다. 돌려주는 id는
     * collection:// 주소다.
     */
    @Override
    public SearchNotionDatabase fetchDatabase(
            long workspaceId,
            String databaseId,
            Duration timeout
    ) {
        return call(
                workspaceId,
                timeout,
                (
                        token,
                        deadline
                ) -> {
                    String dataSourceUrl = dataSourceUrl(
                            token,
                            databaseId,
                            deadline
                    );
                    return results.database(
                            dataSourceUrl,
                            fetch(
                                    token,
                                    dataSourceUrl,
                                    deadline
                            )
                    );
                }
        );
    }

    @Override
    public SearchNotionCreatedPage createPage(
            long workspaceId,
            SearchNotionPageCreation creation,
            Duration timeout
    ) {
        Map<String, Object> page = new LinkedHashMap<>();
        page.put(
                "properties",
                properties(creation)
        );
        page.put(
                "content",
                creation.content()
        );
        Map<String, Object> arguments = Map.of(
                "parent",
                parent(creation),
                "pages",
                List.of(page),
                "allow_async",
                false
        );
        return call(
                workspaceId,
                timeout,
                (
                        token,
                        deadline
                ) -> results.createdPage(
                        tool(
                                token,
                                "notion-create-pages",
                                arguments,
                                deadline,
                                NotionToolAccess.WRITE
                        )
                )
        );
    }

    private <T> T call(
            long workspaceId,
            Duration timeout,
            NotionOperation<T> operation
    ) {
        Instant deadline = clock.instant()
                .plus(timeout);
        NotionConnection connection = connections.findByWorkspaceId(workspaceId)
                .orElseThrow(() -> new SearchNotionException(SearchNotionFailure.NOT_CONNECTED));
        if (!connection.isConnected()) {
            throw new SearchNotionException(SearchNotionFailure.CONNECTION_EXPIRED);
        }
        String used = connection.getEncryptedAccessToken();
        if (isAccessTokenExpired(connection)) {
            return afterRefresh(
                    workspaceId,
                    used,
                    deadline,
                    operation
            );
        }
        try {
            return operation.run(
                    cipher.decrypt(used),
                    deadline
            );
        } catch (NotionMcpUnauthorizedException exception) {
            return afterRefresh(
                    workspaceId,
                    used,
                    deadline,
                    operation
            );
        }
    }

    /**
     * 갱신한 토큰도 거절되면 연결을 만료로 바꾸지 않고 FAILED로 끝낸다(갱신은 호출 하나에 한 번만 한다).
     */
    private <T> T afterRefresh(
            long workspaceId,
            String used,
            Instant deadline,
            NotionOperation<T> operation
    ) {
        String token = refresher.refresh(
                workspaceId,
                used,
                deadline
        );
        try {
            return operation.run(
                    token,
                    deadline
            );
        } catch (NotionMcpUnauthorizedException exception) {
            throw new SearchNotionException(
                    SearchNotionFailure.FAILED,
                    null,
                    exception
            );
        }
    }

    private boolean isAccessTokenExpired(NotionConnection connection) {
        Instant expiresAt = connection.getAccessTokenExpiresAt();
        return expiresAt != null && !clock.instant()
                .isBefore(expiresAt);
    }

    private String dataSourceUrl(
            String token,
            String databaseId,
            Instant deadline
    ) {
        if (databaseId.startsWith(NotionToolResults.DATA_SOURCE_PREFIX)) {
            return databaseId;
        }
        return results.dataSourceUrl(
                fetch(
                        token,
                        databaseId,
                        deadline
                )
        )
                .orElseThrow(() -> new SearchNotionException(SearchNotionFailure.NOT_FOUND));
    }

    private JsonNode fetch(
            String token,
            String id,
            Instant deadline
    ) {
        return tool(
                token,
                "notion-fetch",
                Map.of(
                        "id",
                        id
                ),
                deadline,
                NotionToolAccess.READ
        );
    }

    private JsonNode tool(
            String token,
            String name,
            Map<String, Object> arguments,
            Instant deadline,
            NotionToolAccess access
    ) {
        return results.requireSuccess(
                transport.callTool(
                        token,
                        name,
                        arguments,
                        deadline
                ),
                access
        );
    }

    private Map<String, String> parent(SearchNotionPageCreation creation) {
        if (creation.parentKind() == SearchWriteCardLocationKind.DATABASE) {
            return Map.of(
                    "data_source_id",
                    creation.parentId()
                            .replaceFirst(
                                    "^" + NotionToolResults.DATA_SOURCE_PREFIX,
                                    ""
                            )
            );
        }
        return Map.of(
                "page_id",
                creation.parentId()
        );
    }

    /**
     * 일반 페이지 아래면 title만, DB 아래면 DB 속성 이름을 키로 쓴다. 날짜는 date:{이름}:start와
     * is_datetime=0으로 나누고, 이름이 id나 url인 속성은 노션 규칙대로 userDefined: 접두를 붙인다.
     */
    private Map<String, Object> properties(SearchNotionPageCreation creation) {
        Map<String, Object> values = new LinkedHashMap<>();
        if (creation.parentKind() != SearchWriteCardLocationKind.DATABASE) {
            values.put(
                    "title",
                    creation.title()
            );
            return values;
        }
        for (SearchWriteCardDatabaseField field : creation.databaseFields()) {
            putField(
                    values,
                    field
            );
        }
        return values;
    }

    private void putField(
            Map<String, Object> values,
            SearchWriteCardDatabaseField field
    ) {
        if (SearchWriteCardDatabaseFields.DATE_TYPE.equals(field.propertyType())) {
            values.put(
                    "date:" + field.name() + ":start",
                    field.value()
            );
            values.put(
                    "date:" + field.name() + ":is_datetime",
                    0
            );
            return;
        }
        values.put(
                propertyKey(field.name()),
                field.value()
        );
    }

    private String propertyKey(String name) {
        if (RESERVED_PROPERTY_NAMES.contains(name.toLowerCase())) {
            return "userDefined:" + name;
        }
        return name;
    }

    @FunctionalInterface
    private interface NotionOperation<T> {

        T run(
                String accessToken,
                Instant deadline
        );
    }
}
