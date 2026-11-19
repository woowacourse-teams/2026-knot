package com.knot.backend.search.infrastructure.notion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.knot.backend.global.config.SearchNotionProperties;
import com.knot.backend.search.application.notion.SearchNotionCreatedPage;
import com.knot.backend.search.application.notion.SearchNotionDatabase;
import com.knot.backend.search.application.notion.SearchNotionException;
import com.knot.backend.search.application.notion.SearchNotionFailure;
import com.knot.backend.search.application.notion.SearchNotionObjectKind;
import com.knot.backend.search.application.notion.SearchNotionPage;
import com.knot.backend.search.application.notion.SearchNotionPageCreation;
import com.knot.backend.search.application.notion.SearchNotionSearchHit;
import com.knot.backend.search.application.notion.SearchNotionTokenCipher;
import com.knot.backend.search.domain.model.notion.NotionConnection;
import com.knot.backend.search.domain.model.notion.NotionConnectionGrant;
import com.knot.backend.search.domain.model.notion.SearchWriteCardDatabaseField;
import com.knot.backend.search.domain.model.notion.SearchWriteCardDatabaseFields;
import com.knot.backend.search.domain.model.notion.SearchWriteCardLocationKind;
import com.knot.backend.search.domain.repository.notion.NotionConnectionRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

class NotionMcpSearchNotionClientTest {
    private static final long WORKSPACE_ID = 7;
    private static final Duration TIMEOUT = Duration.ofSeconds(5);
    private static final String KEY = "eKElXGj4+A3yala4lakBsWUXqUkIiYFZqCrEx84y2cM=";

    private final ObjectMapper mapper = JsonMapper.builder()
            .build();
    private final Clock clock = Clock.systemUTC();
    private final MemoryConnections connections = new MemoryConnections();
    private FakeNotionServer notion;
    private SearchNotionTokenCipher cipher;
    private NotionMcpTransport transport;
    private NotionMcpSearchNotionClient client;

    @BeforeEach
    void setUp() throws Exception {
        notion = new FakeNotionServer(mapper);
        SearchNotionProperties properties = new SearchNotionProperties();
        properties.setMcpUrl(
                notion.mcpUrl()
                        .toString()
        );
        properties.setTokenEndpoint(
                notion.tokenUrl()
                        .toString()
        );
        properties.setClientId("knot-client");
        properties.setRedirectUri("https://api.knoted.kr/api/v1/search/notion-connection/callback");
        properties.setTokenEncryptionKey(KEY);
        cipher = new SearchNotionTokenCipher(properties);
        NotionHttp http = new NotionHttp(clock);
        transport = new NotionMcpTransport(
                http,
                mapper,
                properties
        );
        NotionToolResults results = new NotionToolResults(mapper);
        NotionOAuthClient oauth = new NotionOAuthClient(
                http,
                transport,
                results,
                mapper,
                properties,
                clock
        );
        client = new NotionMcpSearchNotionClient(
                connections,
                new NotionTokenRefresher(
                        connections,
                        oauth,
                        cipher,
                        clock
                ),
                transport,
                results,
                cipher,
                properties,
                clock
        );
    }

    @AfterEach
    void tearDown() {
        notion.close();
    }

    @Test
    @DisplayName("저장한 토큰을 풀어 MCP 세션을 열고 검색 결과에서 페이지와 DB만 돌려준다")
    void search_success() {
        // given
        connect(
                Instant.now()
                        .plusSeconds(3600)
        );
        notion.tools(
                params -> FakeNotionServer.Response.tool(
                        FakeNotionServer.textResult(
                                """
                                        {"type":"workspace_search","results":[
                                        {"id":"p1","title":"회의록","url":"https://www.notion.so/p1","type":"page","highlight":"결정 사항","path":"팀 위키 / 회의"},
                                        {"id":"d1","title":"회의 DB","url":"https://www.notion.so/d1","object":"database","path":["팀 위키",{"title":"DB"}]},
                                        {"id":"u1","title":"도넛","type":"user"}]}
                                        """,
                                false
                        )
                )
        );

        // when
        List<SearchNotionSearchHit> hits = client.search(
                WORKSPACE_ID,
                "회의",
                TIMEOUT
        );

        // then
        assertThat(hits).containsExactly(
                new SearchNotionSearchHit(
                        "p1",
                        "회의록",
                        "https://www.notion.so/p1",
                        SearchNotionObjectKind.PAGE,
                        "팀 위키 / 회의",
                        "결정 사항"
                ),
                new SearchNotionSearchHit(
                        "d1",
                        "회의 DB",
                        "https://www.notion.so/d1",
                        SearchNotionObjectKind.DATABASE,
                        "팀 위키 / DB",
                        ""
                )
        );
        assertThat(notion.toolCalls()).containsExactly("notion-search");
        assertThat(
                notion.toolArguments()
                        .getFirst()
                        .path("page_size")
                        .asInt()
        ).isEqualTo(10);
        assertThat(notion.sessions()).containsOnly(FakeNotionServer.SESSION);
        assertThat(notion.authorizations()).containsOnly("Bearer access-old");
    }

    @Test
    @DisplayName("text/event-stream 응답에서 알림을 건너뛰고 요청 id의 응답을 읽는다")
    void search_eventStream() {
        // given
        connect(null);
        notion.useEventStream();
        notion.tools(
                params -> FakeNotionServer.Response.tool(
                        FakeNotionServer.textResult(
                                """
                                        {"results":[{"id":"p1","title":"회의록","url":"u","type":"page"}]}
                                        """,
                                false
                        )
                )
        );

        // when
        List<SearchNotionSearchHit> hits = client.search(
                WORKSPACE_ID,
                "회의",
                TIMEOUT
        );

        // then
        assertThat(hits).extracting(SearchNotionSearchHit::id)
                .containsExactly("p1");
    }

    @Test
    @DisplayName("HTTP 401이면 refresh로 회전한 토큰을 함께 저장하고 한 번 다시 부른다")
    void search_refreshAfterUnauthorized() {
        // given
        connect(
                Instant.now()
                        .plusSeconds(3600)
        );
        notion.acceptToken("access-new");
        notion.tokenResponse(
                FakeNotionServer.Response.json(
                        200,
                        "{\"access_token\":\"access-new\",\"refresh_token\":\"refresh-new\",\"expires_in\":28800}"
                )
        );
        notion.tools(
                params -> FakeNotionServer.Response.tool(
                        FakeNotionServer.textResult(
                                "{\"results\":[]}",
                                false
                        )
                )
        );

        // when
        List<SearchNotionSearchHit> hits = client.search(
                WORKSPACE_ID,
                "회의",
                TIMEOUT
        );

        // then
        assertThat(hits).isEmpty();
        assertThat(notion.tokenRequests()).singleElement()
                .satisfies(
                        form -> assertThat(form).contains(
                                "grant_type=refresh_token",
                                "refresh_token=refresh-old",
                                "resource=" + java.net.URLEncoder.encode(
                                        notion.mcpUrl()
                                                .toString(),
                                        java.nio.charset.StandardCharsets.UTF_8
                                ),
                                "client_id=knot-client"
                        )
                );
        NotionConnection saved = connections.current();
        assertThat(cipher.decrypt(saved.getEncryptedAccessToken())).isEqualTo("access-new");
        assertThat(cipher.decrypt(saved.getEncryptedRefreshToken())).isEqualTo("refresh-new");
        assertThat(saved.getAccessTokenExpiresAt()).isAfter(
                Instant.now()
                        .plusSeconds(28000)
        );
        assertThat(saved.isConnected()).isTrue();
    }

    @Test
    @DisplayName("만료 시각이 지난 access token은 노션을 부르기 전에 갱신한다")
    void search_refreshBeforeExpiredToken() {
        // given
        connect(
                Instant.now()
                        .minusSeconds(1)
        );
        notion.acceptToken("access-new");
        notion.tokenResponse(
                FakeNotionServer.Response.json(
                        200,
                        "{\"access_token\":\"access-new\",\"refresh_token\":\"refresh-new\",\"expires_in\":28800}"
                )
        );
        notion.tools(
                params -> FakeNotionServer.Response.tool(
                        FakeNotionServer.textResult(
                                "{\"results\":[]}",
                                false
                        )
                )
        );

        // when
        client.search(
                WORKSPACE_ID,
                "회의",
                TIMEOUT
        );

        // then
        assertThat(notion.authorizations()).doesNotContain("Bearer access-old");
        assertThat(notion.tokenRequests()).hasSize(1);
    }

    @Test
    @DisplayName("refresh가 invalid_grant로 거절되면 연결을 만료로 바꾸고 다시 부르지 않는다")
    void search_refreshRevoked() {
        // given
        connect(
                Instant.now()
                        .plusSeconds(3600)
        );
        notion.acceptToken("access-other");
        notion.tokenResponse(
                FakeNotionServer.Response.json(
                        400,
                        "{\"error\":\"invalid_grant\",\"error_description\":\"비공개 설명\"}"
                )
        );

        // when & then
        assertThatThrownBy(
                () -> client.search(
                        WORKSPACE_ID,
                        "회의",
                        TIMEOUT
                )
        ).isInstanceOf(SearchNotionException.class)
                .extracting("failure")
                .isEqualTo(SearchNotionFailure.CONNECTION_EXPIRED);
        assertThat(
                connections.current()
                        .isConnected()
        ).isFalse();
        assertThat(notion.toolCalls()).isEmpty();
    }

    @Test
    @DisplayName("만료된 연결이면 노션을 부르지 않고, 연결이 없으면 연결 없음으로 알린다")
    void search_notUsableConnection() {
        // given
        NotionConnection connection = connect(null);
        connection.expire(Instant.now());

        // when & then
        assertThatThrownBy(
                () -> client.search(
                        WORKSPACE_ID,
                        "회의",
                        TIMEOUT
                )
        ).extracting("failure")
                .isEqualTo(SearchNotionFailure.CONNECTION_EXPIRED);
        assertThatThrownBy(
                () -> client.search(
                        WORKSPACE_ID + 1,
                        "회의",
                        TIMEOUT
                )
        ).extracting("failure")
                .isEqualTo(SearchNotionFailure.NOT_CONNECTED);
        assertThat(notion.requestCount()).isZero();
    }

    @Test
    @DisplayName("HTTP 429면 Retry-After를 대기 시간으로 알린다")
    void search_httpRateLimited() {
        // given
        connect(null);
        notion.tools(
                params -> new FakeNotionServer.Response(
                        429,
                        "",
                        Map.of(
                                "Retry-After",
                                "7"
                        )
                )
        );

        // when & then
        assertThatThrownBy(
                () -> client.search(
                        WORKSPACE_ID,
                        "회의",
                        TIMEOUT
                )
        ).isInstanceOfSatisfying(
                SearchNotionException.class,
                exception -> {
                    assertThat(exception.failure()).isEqualTo(SearchNotionFailure.RATE_LIMITED);
                    assertThat(exception.retryAfter()).contains(Duration.ofSeconds(7));
                }
        );
    }

    @Test
    @DisplayName("도구 결과의 rate_limited 오류면 retry_after_seconds를 대기 시간으로 알리고 응답 본문은 메시지에 싣지 않는다")
    void search_toolRateLimited() {
        // given
        connect(null);
        notion.tools(
                params -> FakeNotionServer.Response.tool(
                        """
                                {"content":[{"type":"text","text":"비공개 본문"}],"isError":true,
                                "structuredContent":{"error":{"code":"rate_limited","retry_after_seconds":3,"rate_limit_reason":"mcp_tool_rate_limit"}}}
                                """
                )
        );

        // when & then
        assertThatThrownBy(
                () -> client.search(
                        WORKSPACE_ID,
                        "회의",
                        TIMEOUT
                )
        ).isInstanceOfSatisfying(
                SearchNotionException.class,
                exception -> {
                    assertThat(exception.failure()).isEqualTo(SearchNotionFailure.RATE_LIMITED);
                    assertThat(exception.retryAfter()).contains(Duration.ofSeconds(3));
                    assertThat(exception.getMessage()).doesNotContain("비공개");
                }
        );
    }

    @Test
    @DisplayName("호출 시간 한도를 넘기면 시간 초과로 끊는다")
    void search_timeout() {
        // given
        connect(null);
        notion.tools(params -> {
            sleep(Duration.ofSeconds(3));
            return FakeNotionServer.Response.tool(
                    FakeNotionServer.textResult(
                            "{\"results\":[]}",
                            false
                    )
            );
        });

        // when & then
        assertThatThrownBy(
                () -> client.search(
                        WORKSPACE_ID,
                        "회의",
                        Duration.ofMillis(500)
                )
        ).extracting("failure")
                .isEqualTo(SearchNotionFailure.TIMEOUT);
    }

    @Test
    @DisplayName("허용하지 않은 도구는 노션에 보내지 않고 거절한다")
    void callTool_notAllowed() {
        // when & then
        assertThatThrownBy(
                () -> transport.callTool(
                        "access-old",
                        "notion-delete-page",
                        Map.of(),
                        Instant.now()
                                .plus(TIMEOUT)
                )
        ).extracting("failure")
                .isEqualTo(SearchNotionFailure.FAILED);
        assertThat(notion.requestCount()).isZero();
    }

    @Test
    @DisplayName("페이지를 fetch해 제목, 주소, 본문 마크다운을 읽는다")
    void fetchPage_success() {
        // given
        connect(null);
        notion.tools(
                params -> FakeNotionServer.Response.tool(
                        FakeNotionServer.textResult(
                                """
                                        {"metadata":{"type":"page"},"title":"회의록","url":"https://www.notion.so/p1","text":"# 결정\\n- 배포"}
                                        """,
                                false
                        )
                )
        );

        // when
        SearchNotionPage page = client.fetchPage(
                WORKSPACE_ID,
                "p1",
                TIMEOUT
        );

        // then
        assertThat(page).isEqualTo(
                new SearchNotionPage(
                        "p1",
                        "회의록",
                        "https://www.notion.so/p1",
                        "# 결정\n- 배포"
                )
        );
        assertThat(
                notion.toolArguments()
                        .getFirst()
                        .path("id")
                        .asString()
        ).isEqualTo("p1");
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({"object_not_found,NOT_FOUND", "restricted_resource,NOT_FOUND", "unknown_error,FAILED"})
    @DisplayName("읽기에서 볼 수 없는 페이지는 없음으로 알린다")
    void fetchPage_toolError(
            String code,
            SearchNotionFailure expected
    ) {
        // given
        connect(null);
        notion.tools(
                params -> FakeNotionServer.Response.tool(
                        FakeNotionServer.textResult(
                                "{\"error\":{\"code\":\"" + code + "\"}}",
                                true
                        )
                )
        );

        // when & then
        assertThatThrownBy(
                () -> client.fetchPage(
                        WORKSPACE_ID,
                        "p1",
                        TIMEOUT
                )
        ).extracting("failure")
                .isEqualTo(expected);
    }

    @Test
    @DisplayName("DB를 fetch해 데이터 소스를 찾고 속성 이름과 형식을 읽는다")
    void fetchDatabase_success() {
        // given
        connect(null);
        notion.tools(params -> {
            String id = params.path("arguments")
                    .path("id")
                    .asString();
            if (id.startsWith("collection://")) {
                return FakeNotionServer.Response.tool(
                        FakeNotionServer.textResult(
                                """
                                        <data-source url="collection://ds-1">
                                        <data-source-state>
                                        {"name":"회의 DB","schema":{"이름":{"name":"이름","type":"title"},"날짜":{"type":"date"},"참석자":{"type":"people"}}}
                                        </data-source-state>
                                        </data-source>
                                        """,
                                false
                        )
                );
            }
            return FakeNotionServer.Response.tool(
                    FakeNotionServer.textResult(
                            """
                                    <database url="https://www.notion.so/d1">
                                    <data-sources><data-source url="collection://ds-1"></data-source></data-sources>
                                    </database>
                                    """,
                            false
                    )
            );
        });

        // when
        SearchNotionDatabase database = client.fetchDatabase(
                WORKSPACE_ID,
                "d1",
                TIMEOUT
        );

        // then
        assertThat(database.id()).isEqualTo("collection://ds-1");
        assertThat(database.title()).isEqualTo("회의 DB");
        assertThat(database.properties()).containsExactlyInAnyOrder(
                new SearchWriteCardDatabaseFields.Property(
                        "이름",
                        "title"
                ),
                new SearchWriteCardDatabaseFields.Property(
                        "날짜",
                        "date"
                ),
                new SearchWriteCardDatabaseFields.Property(
                        "참석자",
                        "people"
                )
        );
        assertThat(notion.toolArguments()).extracting(
                arguments -> arguments.path("id")
                        .asString()
        )
                .containsExactly(
                        "d1",
                        "collection://ds-1"
                );
    }

    @Test
    @DisplayName("DB 아래에 만들 때 데이터 소스 ID와 속성 이름 키로 보내고 만든 페이지 주소를 돌려준다")
    void createPage_database() {
        // given
        connect(null);
        notion.tools(
                params -> FakeNotionServer.Response.tool(
                        FakeNotionServer.textResult(
                                "{\"pages\":[{\"id\":\"new-1\",\"url\":\"https://www.notion.so/new1\",\"properties\":{}}]}",
                                false
                        )
                )
        );
        SearchNotionPageCreation creation = new SearchNotionPageCreation(
                SearchWriteCardLocationKind.DATABASE,
                "collection://ds-1",
                "주간 회의",
                List.of(
                        new SearchWriteCardDatabaseField(
                                "이름",
                                "주간 회의",
                                "title"
                        ),
                        new SearchWriteCardDatabaseField(
                                "날짜",
                                "2026-11-19",
                                "date"
                        )
                ),
                "# 결정"
        );

        // when
        SearchNotionCreatedPage created = client.createPage(
                WORKSPACE_ID,
                creation,
                TIMEOUT
        );

        // then
        assertThat(created).isEqualTo(
                new SearchNotionCreatedPage(
                        "new-1",
                        "https://www.notion.so/new1"
                )
        );
        String arguments = notion.toolArguments()
                .getFirst()
                .toString();
        assertThat(arguments).contains(
                "\"data_source_id\":\"ds-1\"",
                "\"이름\":\"주간 회의\"",
                "\"date:날짜:start\":\"2026-11-19\"",
                "\"date:날짜:is_datetime\":0",
                "\"content\":\"# 결정\"",
                "\"allow_async\":false"
        );
    }

    @Test
    @DisplayName("일반 페이지 아래에 만들 때 page_id와 title만 보낸다")
    void createPage_page() {
        // given
        connect(null);
        notion.tools(
                params -> FakeNotionServer.Response.tool(
                        FakeNotionServer.textResult(
                                "{\"pages\":[{\"id\":\"new-1\",\"url\":\"https://www.notion.so/new1\"}]}",
                                false
                        )
                )
        );

        // when
        client.createPage(
                WORKSPACE_ID,
                new SearchNotionPageCreation(
                        SearchWriteCardLocationKind.PAGE,
                        "p1",
                        "주간 회의",
                        List.of(),
                        "# 결정"
                ),
                TIMEOUT
        );

        // then
        assertThat(
                notion.toolArguments()
                        .getFirst()
                        .toString()
        ).contains(
                "\"parent\":{\"page_id\":\"p1\"}",
                "\"properties\":{\"title\":\"주간 회의\"}"
        );
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({"object_not_found,PERMISSION_DENIED", "restricted_resource,PERMISSION_DENIED",
            "validation_error,CONTENT_TOO_LONG"})
    @DisplayName("쓰기의 권한 없음은 권한 없음으로, 내용 검증 오류는 내용이 너무 김으로 알린다")
    void createPage_toolError(
            String code,
            SearchNotionFailure expected
    ) {
        // given
        connect(null);
        notion.tools(
                params -> FakeNotionServer.Response.tool(
                        FakeNotionServer.textResult(
                                "{\"code\":\"" + code + "\",\"message\":\"비공개\"}",
                                true
                        )
                )
        );

        // when & then
        assertThatThrownBy(
                () -> client.createPage(
                        WORKSPACE_ID,
                        new SearchNotionPageCreation(
                                SearchWriteCardLocationKind.PAGE,
                                "p1",
                                "제목",
                                List.of(),
                                "본문"
                        ),
                        TIMEOUT
                )
        ).extracting("failure")
                .isEqualTo(expected);
    }

    private NotionConnection connect(Instant accessTokenExpiresAt) {
        NotionConnection connection = NotionConnection.connect(
                WORKSPACE_ID,
                3,
                new NotionConnectionGrant(
                        "notion-ws",
                        "Knot 팀",
                        null,
                        cipher.encrypt("access-old"),
                        cipher.encrypt("refresh-old"),
                        accessTokenExpiresAt
                ),
                Instant.now()
        );
        connections.save(connection);
        return connection;
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException exception) {
            Thread.currentThread()
                    .interrupt();
        }
    }

    private static final class MemoryConnections implements NotionConnectionRepository {

        private final Map<Long, NotionConnection> values = new HashMap<>();

        NotionConnection current() {
            return values.get(WORKSPACE_ID);
        }

        @Override
        public NotionConnection save(NotionConnection connection) {
            values.put(
                    connection.getWorkspaceId(),
                    connection
            );
            return connection;
        }

        @Override
        public Optional<NotionConnection> findByWorkspaceId(long workspaceId) {
            return Optional.ofNullable(values.get(workspaceId));
        }

        @Override
        public Optional<NotionConnection> findByWorkspaceIdForUpdate(long workspaceId) {
            return findByWorkspaceId(workspaceId);
        }

        @Override
        public void delete(NotionConnection connection) {
            values.remove(connection.getWorkspaceId());
        }

        @Override
        public void flush() {}
    }
}
