package com.knot.backend.search.application.notion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.knot.backend.global.config.SearchNotionProperties;
import com.knot.backend.search.application.dto.result.SearchUsePlan;
import com.knot.backend.search.domain.model.SearchIntent;
import com.knot.backend.search.domain.model.notion.NotionConnection;
import com.knot.backend.search.domain.model.notion.NotionConnectionGrant;
import com.knot.backend.search.domain.model.notion.SearchNotionNoticeReason;
import com.knot.backend.search.domain.model.notion.SearchWriteCardDatabaseFields;
import com.knot.backend.search.domain.model.notion.SearchWriteCardLocationKind;
import com.knot.backend.search.domain.repository.notion.NotionConnectionRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.ObjectProvider;

class SearchNotionReaderTest {
    private static final long WORKSPACE_ID = 1;
    private static final Instant NOW = Instant.parse("2026-11-19T00:00:00Z");

    private final FakeNotionClient client = new FakeNotionClient();
    private final NotionConnectionRepository connections = mock(NotionConnectionRepository.class);
    private final SearchNotionProperties properties = new SearchNotionProperties();
    private SearchNotionReader reader;

    @BeforeEach
    void setUp() {
        properties.setReadTimeout(Duration.ofMillis(300));
        properties.setLocationTimeout(Duration.ofMillis(300));
        properties.setPageContentCharacterLimit(20);
        @SuppressWarnings("unchecked")
        ObjectProvider<SearchNotionClient> clients = mock(ObjectProvider.class);
        when(clients.getObject()).thenReturn(client);
        reader = new SearchNotionReader(
                clients,
                connections,
                properties
        );
        connected();
    }

    @AfterEach
    void tearDown() {
        reader.close();
    }

    @Test
    @DisplayName("검색한 페이지마다 본문을 읽고 DB는 읽기 대상에서 뺀다")
    void read_success_fetchesEveryPage() {
        client.hits(
                "출시 계획",
                hit(
                        "p1",
                        SearchNotionObjectKind.PAGE,
                        ""
                ),
                hit(
                        "db1",
                        SearchNotionObjectKind.DATABASE,
                        ""
                ),
                hit(
                        "p2",
                        SearchNotionObjectKind.PAGE,
                        ""
                )
        );
        client.page(
                "p1",
                "12월 출시"
        );
        client.page(
                "p2",
                "베타 일정"
        );
        SearchNotionReadResult result = read("출시 계획");
        assertThat(result.notice()).isEmpty();
        assertThat(result.pages()).containsExactly(
                new SearchNotionPage(
                        "p1",
                        "p1 제목",
                        "https://notion.so/p1",
                        "12월 출시"
                ),
                new SearchNotionPage(
                        "p2",
                        "p2 제목",
                        "https://notion.so/p2",
                        "베타 일정"
                )
        );
        assertThat(client.fetched).containsExactlyInAnyOrder(
                "p1",
                "p2"
        );
    }

    @Test
    @DisplayName("노션 연결이 없으면 노션을 부르지 않고 연결 없음을 알린다")
    void read_failure_notConnected() {
        when(connections.findByWorkspaceId(WORKSPACE_ID)).thenReturn(Optional.empty());
        assertThat(read("출시 계획").notice()).contains(SearchNotionNoticeReason.NOT_CONNECTED);
        assertThat(client.searched).isEmpty();
    }

    @Test
    @DisplayName("만료된 연결이면 노션을 부르지 않고 연결 만료를 알린다")
    void read_failure_expiredConnection() {
        NotionConnection connection = connection();
        connection.expire(NOW);
        when(connections.findByWorkspaceId(WORKSPACE_ID)).thenReturn(Optional.of(connection));
        assertThat(read("출시 계획").notice()).contains(SearchNotionNoticeReason.CONNECTION_EXPIRED);
        assertThat(client.searched).isEmpty();
    }

    @Test
    @DisplayName("맞는 페이지가 없으면 노션 페이지 없음을 알린다")
    void read_failure_noPage() {
        client.hits(
                "출시 계획",
                hit(
                        "db1",
                        SearchNotionObjectKind.DATABASE,
                        ""
                )
        );
        assertThat(read("출시 계획").notice()).contains(SearchNotionNoticeReason.PAGE_NOT_FOUND);
    }

    @Test
    @DisplayName("읽기 시간 한도를 넘기면 읽은 페이지를 버리고 읽지 못함을 알린다")
    void read_failure_timeout() {
        client.hits(
                "출시 계획",
                hit(
                        "p1",
                        SearchNotionObjectKind.PAGE,
                        ""
                )
        );
        client.slowPage(
                "p1",
                Duration.ofSeconds(2)
        );
        SearchNotionReadResult result = read("출시 계획");
        assertThat(result.notice()).contains(SearchNotionNoticeReason.READ_FAILED);
        assertThat(result.pages()).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(value = SearchNotionFailure.class, names = {"CONNECTION_EXPIRED", "NOT_CONNECTED", "RATE_LIMITED",
            "FAILED"})
    @DisplayName("노션 호출이 연결 만료로 거절되면 만료를, 나머지 실패는 읽지 못함을 알린다")
    void read_failure_notionException(SearchNotionFailure failure) {
        client.failSearch(new SearchNotionException(failure));
        SearchNotionNoticeReason expected = SearchNotionNoticeReason.READ_FAILED;
        if (failure == SearchNotionFailure.CONNECTION_EXPIRED) {
            expected = SearchNotionNoticeReason.CONNECTION_EXPIRED;
        }
        if (failure == SearchNotionFailure.NOT_CONNECTED) {
            expected = SearchNotionNoticeReason.NOT_CONNECTED;
        }
        assertThat(read("출시 계획").notice()).contains(expected);
    }

    @Test
    @DisplayName("본문이 길면 앞에서 자르지 않고 검색 결과 조각이 나온 곳 근처를 넘긴다")
    void read_success_excerptAroundHighlight() {
        client.hits(
                "출시",
                hit(
                        "p1",
                        SearchNotionObjectKind.PAGE,
                        "출시일은 12월"
                )
        );
        client.page(
                "p1",
                "가".repeat(40) + "출시일은 12월 8일" + "나".repeat(40)
        );
        String content = read("출시").pages()
                .getFirst()
                .content();
        assertThat(content).hasSize(20)
                .contains("출시일은");
    }

    @Test
    @DisplayName("본문을 자를 때 조각을 찾지 못하면 검색어로 찾고 검색어도 없으면 앞에서부터 자른다")
    void excerpt_success_fallsBackToQueryThenFront() {
        String content = "가".repeat(30) + "기획서" + "나".repeat(30);
        assertThat(
                SearchNotionReader.excerpt(
                        content,
                        List.of(
                                "없는 조각",
                                "기획서"
                        ),
                        10
                )
        ).contains("기획서")
                .hasSize(10);
        assertThat(
                SearchNotionReader.excerpt(
                        content,
                        List.of("없는 조각"),
                        10
                )
        ).isEqualTo("가".repeat(10));
        assertThat(
                SearchNotionReader.excerpt(
                        "짧은 본문",
                        List.of(),
                        10
                )
        ).isEqualTo("짧은 본문");
    }

    @Test
    @DisplayName("본문을 자를 때 이모지 같은 두 글자 문자를 반으로 가르지 않는다")
    void excerpt_success_keepsSurrogatePairs() {
        String content = "😀".repeat(20);
        String excerpt = SearchNotionReader.excerpt(
                content,
                List.of(),
                5
        );
        assertThat(excerpt).isEqualTo("😀😀");
    }

    @Test
    @DisplayName("만들 위치는 노션이 준 순서의 맨 앞 페이지로 정하고 경로를 모르면 이름을 쓴다")
    void locate_success_firstPage() {
        client.hits(
                "회의록",
                hit(
                        "p1",
                        SearchNotionObjectKind.PAGE,
                        ""
                ),
                hit(
                        "db1",
                        SearchNotionObjectKind.DATABASE,
                        ""
                )
        );
        SearchNotionLocation location = locate("회의록").location()
                .orElseThrow();
        assertThat(location.kind()).isEqualTo(SearchWriteCardLocationKind.PAGE);
        assertThat(location.notionId()).isEqualTo("p1");
        assertThat(location.name()).isEqualTo("p1 제목");
        assertThat(location.path()).isEqualTo("p1 제목");
        assertThat(location.notionWorkspaceId()).isEqualTo("notion-ws");
        assertThat(location.structureKnown()).isTrue();
    }

    @Test
    @DisplayName("맨 앞 후보가 DB면 속성 구조를 읽고 DB가 돌려준 id를 위치로 쓴다")
    void locate_success_database() {
        client.hits(
                "회의록 DB",
                hit(
                        "db-search",
                        SearchNotionObjectKind.DATABASE,
                        ""
                )
        );
        client.database(
                "db-search",
                new SearchNotionDatabase(
                        "collection-1",
                        "회의록",
                        "팀 위키 / 회의록",
                        List.of(
                                new SearchWriteCardDatabaseFields.Property(
                                        "이름",
                                        "title"
                                )
                        )
                )
        );
        SearchNotionLocation location = locate("회의록 DB").location()
                .orElseThrow();
        assertThat(location.kind()).isEqualTo(SearchWriteCardLocationKind.DATABASE);
        assertThat(location.notionId()).isEqualTo("collection-1");
        assertThat(location.path()).isEqualTo("팀 위키 / 회의록");
        assertThat(location.properties()).hasSize(1);
        assertThat(location.structureKnown()).isTrue();
    }

    @Test
    @DisplayName("DB 속성 구조를 읽지 못하면 위치는 정하되 구조를 모르는 위치로 둔다")
    void locate_success_unknownDatabaseStructure() {
        client.hits(
                "회의록 DB",
                hit(
                        "db-search",
                        SearchNotionObjectKind.DATABASE,
                        ""
                )
        );
        SearchNotionLocation location = locate("회의록 DB").location()
                .orElseThrow();
        assertThat(location.notionId()).isEqualTo("db-search");
        assertThat(location.properties()).isEmpty();
        assertThat(location.structureKnown()).isFalse();
    }

    @Test
    @DisplayName("위치를 말하지 않았거나 후보가 없거나 검색이 시간 한도를 넘기면 사유를 정한다")
    void locate_failure_skipReasons() {
        assertThat(locate("").skip()).contains(SearchNotionWriteSkip.LOCATION_MISSING);
        assertThat(client.searched).isEmpty();
        assertThat(locate("없는 위치").skip()).contains(SearchNotionWriteSkip.LOCATION_NOT_FOUND);
        client.slowSearch(Duration.ofSeconds(2));
        assertThat(locate("회의록").skip()).contains(SearchNotionWriteSkip.LOCATION_FAILED);
    }

    @Test
    @DisplayName("쓰기 요청에서 연결이 없거나 만료면 위치 검색 없이 연결 사유를 정한다")
    void locate_failure_connection() {
        when(connections.findByWorkspaceId(WORKSPACE_ID)).thenReturn(Optional.empty());
        assertThat(locate("회의록").skip()).contains(SearchNotionWriteSkip.NOT_CONNECTED);
        NotionConnection connection = connection();
        connection.expire(NOW);
        when(connections.findByWorkspaceId(WORKSPACE_ID)).thenReturn(Optional.of(connection));
        assertThat(locate("회의록").skip()).contains(SearchNotionWriteSkip.CONNECTION_EXPIRED);
        client.failSearch(new SearchNotionException(SearchNotionFailure.CONNECTION_EXPIRED));
        connected();
        assertThat(locate("회의록").skip()).contains(SearchNotionWriteSkip.CONNECTION_EXPIRED);
        assertThat(client.searched).hasSize(1);
    }

    @Test
    @DisplayName("읽기와 만들기가 함께 계획되면 둘 다 결과를 낸다")
    void start_success_readAndCreateTogether() {
        client.hits(
                "출시",
                hit(
                        "p1",
                        SearchNotionObjectKind.PAGE,
                        ""
                )
        );
        client.hits(
                "회의록",
                hit(
                        "p2",
                        SearchNotionObjectKind.PAGE,
                        ""
                )
        );
        client.page(
                "p1",
                "본문"
        );
        SearchNotionMaterial material = reader.start(
                WORKSPACE_ID,
                new SearchUsePlan(
                        SearchIntent.MEETING_SEARCH,
                        Optional.of("출시"),
                        Optional.of("회의록")
                )
        )
                .await();
        assertThat(material.pages()).hasSize(1);
        assertThat(material.writeLocation()).isPresent();
        assertThat(material.writeSkip(true)).isEmpty();
        assertThat(material.writeSkip(false)).contains(SearchNotionWriteSkip.MEETING_NOT_FOUND);
    }

    private SearchNotionReadResult read(String query) {
        return reader.start(
                WORKSPACE_ID,
                new SearchUsePlan(
                        SearchIntent.MEETING_SEARCH,
                        Optional.of(query),
                        Optional.empty()
                )
        )
                .await()
                .read()
                .orElseThrow();
    }

    private SearchNotionWritePlan locate(String location) {
        return reader.start(
                WORKSPACE_ID,
                new SearchUsePlan(
                        SearchIntent.MEETING_SEARCH,
                        Optional.empty(),
                        Optional.of(location)
                )
        )
                .await()
                .write()
                .orElseThrow();
    }

    private void connected() {
        when(connections.findByWorkspaceId(WORKSPACE_ID)).thenReturn(Optional.of(connection()));
    }

    private NotionConnection connection() {
        return NotionConnection.connect(
                WORKSPACE_ID,
                2,
                new NotionConnectionGrant(
                        "notion-ws",
                        "팀 노션",
                        null,
                        "encrypted",
                        null,
                        null
                ),
                NOW
        );
    }

    private SearchNotionSearchHit hit(
            String id,
            SearchNotionObjectKind kind,
            String highlight
    ) {
        return new SearchNotionSearchHit(
                id,
                id + " 제목",
                "https://notion.so/" + id,
                kind,
                "",
                highlight
        );
    }

    private static final class FakeNotionClient implements SearchNotionClient {
        private final Map<String, List<SearchNotionSearchHit>> hits = new ConcurrentHashMap<>();
        private final Map<String, Supplier<SearchNotionPage>> pages = new ConcurrentHashMap<>();
        private final Map<String, SearchNotionDatabase> databases = new ConcurrentHashMap<>();
        private final List<String> searched = java.util.Collections.synchronizedList(new ArrayList<>());
        private final List<String> fetched = java.util.Collections.synchronizedList(new ArrayList<>());
        private RuntimeException searchFailure;
        private Duration searchDelay = Duration.ZERO;

        void hits(
                String query,
                SearchNotionSearchHit... results
        ) {
            hits.put(
                    query,
                    List.of(results)
            );
        }

        void page(
                String id,
                String content
        ) {
            pages.put(
                    id,
                    () -> new SearchNotionPage(
                            id,
                            "읽은 제목",
                            "https://notion.so/fetched",
                            content
                    )
            );
        }

        void slowPage(
                String id,
                Duration delay
        ) {
            pages.put(
                    id,
                    () -> {
                        sleep(delay);
                        return new SearchNotionPage(
                                id,
                                "",
                                "",
                                "늦은 본문"
                        );
                    }
            );
        }

        void database(
                String id,
                SearchNotionDatabase database
        ) {
            databases.put(
                    id,
                    database
            );
        }

        void failSearch(RuntimeException failure) {
            searchFailure = failure;
        }

        void slowSearch(Duration delay) {
            searchDelay = delay;
        }

        @Override
        public List<SearchNotionSearchHit> search(
                long workspaceId,
                String query,
                Duration timeout
        ) {
            searched.add(query);
            sleep(searchDelay);
            if (searchFailure != null) {
                throw searchFailure;
            }
            return hits.getOrDefault(
                    query,
                    List.of()
            );
        }

        @Override
        public SearchNotionPage fetchPage(
                long workspaceId,
                String pageId,
                Duration timeout
        ) {
            fetched.add(pageId);
            return pages.get(pageId)
                    .get();
        }

        @Override
        public SearchNotionDatabase fetchDatabase(
                long workspaceId,
                String databaseId,
                Duration timeout
        ) {
            SearchNotionDatabase database = databases.get(databaseId);
            if (database == null) {
                throw new SearchNotionException(SearchNotionFailure.NOT_FOUND);
            }
            return database;
        }

        @Override
        public SearchNotionCreatedPage createPage(
                long workspaceId,
                SearchNotionPageCreation creation,
                Duration timeout
        ) {
            throw new UnsupportedOperationException("답변 생성은 노션에 쓰지 않는다");
        }

        private static void sleep(Duration delay) {
            if (delay.isZero()) {
                return;
            }
            try {
                Thread.sleep(delay);
            } catch (InterruptedException exception) {
                Thread.currentThread()
                        .interrupt();
            }
        }
    }
}
