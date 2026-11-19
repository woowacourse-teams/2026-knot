package com.knot.backend.search.presentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.knot.backend.auth.domain.AuthTokenProvider;
import com.knot.backend.auth.domain.model.AuthenticatedMember;
import com.knot.backend.search.application.notion.NotionConnectionCallback;
import com.knot.backend.search.application.notion.NotionConnectionCallbackService;
import com.knot.backend.search.application.notion.NotionConnectionOutcome;
import com.knot.backend.search.application.notion.SearchNotionAuthorizationServer;
import com.knot.backend.search.application.notion.SearchNotionException;
import com.knot.backend.search.application.notion.SearchNotionFailure;
import com.knot.backend.search.application.notion.SearchNotionIssuedGrant;
import com.knot.backend.search.application.notion.SearchNotionTokenCipher;
import com.knot.backend.search.domain.model.notion.NotionConnection;
import com.knot.backend.search.domain.model.notion.NotionConnectionGrant;
import com.knot.backend.search.domain.repository.notion.NotionConnectionRepository;
import com.knot.backend.testsupport.TestApplicationProperties;
import com.knot.backend.testsupport.TestcontainersConfiguration;
import jakarta.servlet.http.Cookie;
import java.net.URI;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Tag("acceptance")
@ActiveProfiles("dev")
@SpringBootTest(properties = {"knot.search.notion.client-id=knot-test-client",
        "knot.search.notion.redirect-uri=https://api.knoted.kr/api/v1/search/notion-connection/callback"})
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@TestApplicationProperties
class SearchNotionConnectionAcceptanceTest {
    private static final String PATH = "/api/v1/workspaces/{workspaceId}/search/notion-connection";
    private static final String CALLBACK = "/api/v1/search/notion-connection/callback";
    private static final Instant NOW = Instant.parse("2026-11-19T01:00:00Z");

    @Autowired
    private MockMvc mvc;
    @Autowired
    private JdbcClient jdbc;
    @Autowired
    private AuthTokenProvider tokens;
    @Autowired
    private JsonMapper json;
    @Autowired
    private NotionConnectionRepository connections;
    @Autowired
    private SearchNotionTokenCipher cipher;
    @Autowired
    private NotionConnectionCallbackService callbacks;
    @Autowired
    private FakeAuthorizationServer notion;
    private long workspaceId;
    private long ownerId;
    private long memberId;
    private long otherId;

    @BeforeEach
    void setUp() {
        jdbc.sql("TRUNCATE TABLE members, workspaces RESTART IDENTITY CASCADE")
                .update();
        notion.reset();
        workspaceId = saveWorkspace();
        ownerId = saveMember("소유자");
        memberId = saveMember("도넛");
        otherId = saveMember("흑곰");
        join(
                ownerId,
                "OWNER"
        );
        join(
                memberId,
                "MEMBER"
        );
        join(
                otherId,
                "MEMBER"
        );
    }

    @Test
    @DisplayName("연결이 없으면 200과 NOT_CONNECTED를 주고 나머지 값은 비운다")
    void find_notConnected() throws Exception {
        // when & then
        mvc.perform(
                get(
                        PATH,
                        workspaceId
                ).cookie(cookie(memberId))
        )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("NOT_CONNECTED"))
                .andExpect(jsonPath("$.notionWorkspaceName").value(nullValue()))
                .andExpect(jsonPath("$.notionWorkspaceIcon").value(nullValue()))
                .andExpect(jsonPath("$.connectedBy").value(nullValue()))
                .andExpect(jsonPath("$.canDisconnect").value(false));
    }

    @Test
    @DisplayName("연결됨이면 노션 워크스페이스와 연결한 사람을 주고 연결한 사람은 끊을 수 있다")
    void find_connected() throws Exception {
        // given
        saveConnection(
                memberId,
                false
        );

        // when & then
        mvc.perform(
                get(
                        PATH,
                        workspaceId
                ).cookie(cookie(memberId))
        )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONNECTED"))
                .andExpect(jsonPath("$.notionWorkspaceName").value("Knot 팀"))
                .andExpect(jsonPath("$.notionWorkspaceIcon").value("🪢"))
                .andExpect(jsonPath("$.connectedBy.memberId").value(memberId))
                .andExpect(jsonPath("$.connectedBy.nickname").value("도넛"))
                .andExpect(jsonPath("$.canDisconnect").value(true))
                .andExpect(jsonPath("$.notionWorkspaceId").doesNotExist())
                .andExpect(jsonPath("$.accessToken").doesNotExist());
    }

    @Test
    @DisplayName("만료된 연결은 EXPIRED로 주고 OWNER만 연결한 사람이 아니어도 끊을 수 있다")
    void find_expired() throws Exception {
        // given
        saveConnection(
                memberId,
                true
        );

        // when & then
        mvc.perform(
                get(
                        PATH,
                        workspaceId
                ).cookie(cookie(otherId))
        )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("EXPIRED"))
                .andExpect(jsonPath("$.canDisconnect").value(false));
        mvc.perform(
                get(
                        PATH,
                        workspaceId
                ).cookie(cookie(ownerId))
        )
                .andExpect(jsonPath("$.canDisconnect").value(true));
    }

    @Test
    @DisplayName("Workspace 멤버가 아니면 조회와 연결 시작, 끊기를 403으로 거절한다")
    void outsider_denied() throws Exception {
        // given
        long outsider = saveMember("손님");
        Csrf csrf = csrf();

        // when & then
        mvc.perform(
                get(
                        PATH,
                        workspaceId
                ).cookie(cookie(outsider))
        )
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("WORKSPACE_ACCESS_DENIED"));
        mvc.perform(
                authorization(
                        outsider,
                        "{\"agreed\":true}",
                        csrf
                )
        )
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("WORKSPACE_ACCESS_DENIED"));
        mvc.perform(
                disconnect(
                        outsider,
                        csrf
                )
        )
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("WORKSPACE_ACCESS_DENIED"));
    }

    @Test
    @DisplayName("연결 시작은 동의 화면 주소를 주고 state는 해시로, verifier는 암호화해 저장한다")
    void startAuthorization_success() throws Exception {
        // when
        String url = startAuthorization(memberId);

        // then
        URI uri = URI.create(url);
        Map<String, String> query = query(uri);
        assertThat(url).startsWith("https://mcp.notion.com/authorize?");
        assertThat(query).containsEntry(
                "response_type",
                "code"
        )
                .containsEntry(
                        "client_id",
                        "knot-test-client"
                )
                .containsEntry(
                        "redirect_uri",
                        "https://api.knoted.kr/api/v1/search/notion-connection/callback"
                )
                .containsEntry(
                        "code_challenge_method",
                        "S256"
                )
                .containsEntry(
                        "resource",
                        "https://mcp.notion.com/mcp"
                )
                .containsEntry(
                        "prompt",
                        "consent"
                )
                .containsKeys(
                        "state",
                        "code_challenge"
                );
        Map<String, Object> saved = jdbc.sql("SELECT * FROM search_notion_authorization_states")
                .query()
                .singleRow();
        assertThat(saved.get("state_hash")).isNotEqualTo(query.get("state"));
        assertThat(saved.get("member_id")).isEqualTo(memberId);
        assertThat(
                saved.get("encrypted_code_verifier")
                        .toString()
        ).isNotBlank();
    }

    @Test
    @DisplayName("만료된 연결은 누구나 다시 연결을 시작할 수 있다")
    void startAuthorization_expired() throws Exception {
        // given
        saveConnection(
                memberId,
                true
        );

        // when & then
        assertThat(startAuthorization(otherId)).contains("state=");
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"{\"agreed\":false}", "{}"})
    @DisplayName("안내에 동의하지 않으면 400으로 거절하고 state를 만들지 않는다")
    void startAuthorization_notAgreed(String body) throws Exception {
        // when & then
        mvc.perform(
                authorization(
                        memberId,
                        body,
                        csrf()
                )
        )
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        assertThat(stateCount()).isZero();
    }

    @Test
    @DisplayName("이미 연결됨이면 409로 거절하고 앞선 연결을 바꾸지 않는다")
    void startAuthorization_alreadyConnected() throws Exception {
        // given
        saveConnection(
                memberId,
                false
        );

        // when & then
        mvc.perform(
                authorization(
                        otherId,
                        "{\"agreed\":true}",
                        csrf()
                )
        )
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("NOTION_CONNECTION_ALREADY_CONNECTED"));
        assertThat(connectedMember()).isEqualTo(memberId);
    }

    @Test
    @DisplayName("동의를 마치고 돌아오면 연결을 만들고 토큰은 암호화해 저장한 뒤 CONNECTED로 보낸다")
    void callback_connected() throws Exception {
        // given
        String url = startAuthorization(memberId);

        // when
        callback(
                Map.of(
                        "state",
                        state(url),
                        "code",
                        "code-a",
                        "iss",
                        "https://mcp.notion.com"
                ),
                "CONNECTED"
        );

        // then
        NotionConnection connection = connections.findByWorkspaceId(workspaceId)
                .orElseThrow();
        assertThat(connection.isConnectedBy(memberId)).isTrue();
        assertThat(connection.getNotionWorkspaceName()).isEqualTo("노션 code-a");
        assertThat(connection.getEncryptedAccessToken()).doesNotContain("access-code-a");
        assertThat(cipher.decrypt(connection.getEncryptedAccessToken())).isEqualTo("access-code-a");
        assertThat(cipher.decrypt(connection.getEncryptedRefreshToken())).isEqualTo("refresh-code-a");
        assertThat(stateCount()).isZero();
        assertThat(notion.verifiers()).singleElement()
                .satisfies(
                        verifier -> assertThat(challenge(verifier))
                                .isEqualTo(query(URI.create(url)).get("code_challenge"))
                );
    }

    @Test
    @DisplayName("같은 state로 다시 오면 state를 찾지 못한 것으로 보고 쿼리 없이 기본 화면으로 보낸다")
    void callback_stateUsedOnce() throws Exception {
        // given
        String state = state(startAuthorization(memberId));
        callback(
                Map.of(
                        "state",
                        state,
                        "code",
                        "code-a"
                ),
                "CONNECTED"
        );

        // when & then
        mvc.perform(
                get(CALLBACK).param(
                        "state",
                        state
                )
                        .param(
                                "code",
                                "code-a"
                        )
        )
                .andExpect(status().isFound())
                .andExpect(
                        header().string(
                                "Location",
                                "/workspace"
                        )
                );
        mvc.perform(get(CALLBACK))
                .andExpect(status().isFound())
                .andExpect(
                        header().string(
                                "Location",
                                "/workspace"
                        )
                );
        assertThat(notion.calls()).isEqualTo(1);
    }

    @Test
    @DisplayName("노션 동의 화면에서 그만두면 CANCELLED, 다른 오류와 다른 issuer는 FAILED로 보내고 연결을 만들지 않는다")
    void callback_cancelledOrFailed() throws Exception {
        // when & then
        callback(
                Map.of(
                        "state",
                        state(startAuthorization(memberId)),
                        "error",
                        "access_denied"
                ),
                "CANCELLED"
        );
        callback(
                Map.of(
                        "state",
                        state(startAuthorization(memberId)),
                        "error",
                        "server_error"
                ),
                "FAILED"
        );
        callback(
                Map.of(
                        "state",
                        state(startAuthorization(memberId)),
                        "code",
                        "code-a",
                        "iss",
                        "https://evil.example"
                ),
                "FAILED"
        );
        assertThat(connections.findByWorkspaceId(workspaceId)).isEmpty();
        assertThat(notion.calls()).isZero();
    }

    @Test
    @DisplayName("연결 시작은 state를 callback 경로에만 보내는 HttpOnly 쿠키로도 내려 준다")
    void startAuthorization_stateCookie() throws Exception {
        // when
        MvcResult result = mvc.perform(
                authorization(
                        memberId,
                        "{\"agreed\":true}",
                        csrf()
                )
        )
                .andExpect(status().isOk())
                .andReturn();

        // then
        String url = json.readTree(
                result.getResponse()
                        .getContentAsString()
        )
                .path("authorizationUrl")
                .asString();
        assertThat(
                result.getResponse()
                        .getHeaders("Set-Cookie")
        ).anySatisfy(
                cookie -> assertThat(cookie).contains(
                        SearchNotionConnectionController.STATE_COOKIE + "=" + state(url),
                        "Path=" + CALLBACK,
                        "HttpOnly",
                        "SameSite=Lax"
                )
        );
    }

    @Test
    @DisplayName("연결을 시작한 브라우저의 state 쿠키가 없거나 다르면 노션 토큰을 교환하지 않고 FAILED로 보낸다")
    void callback_otherBrowser() throws Exception {
        // given
        String state = state(startAuthorization(memberId));

        // when & then
        mvc.perform(
                get(CALLBACK).param(
                        "state",
                        state
                )
                        .param(
                                "code",
                                "code-a"
                        )
                        .cookie(
                                new Cookie(
                                        SearchNotionConnectionController.STATE_COOKIE,
                                        "other-state"
                                )
                        )
        )
                .andExpect(status().isFound())
                .andExpect(
                        header().string(
                                "Location",
                                "/workspace/" + workspaceId + "/apps?notionConnection=FAILED"
                        )
                );
        assertThat(connections.findByWorkspaceId(workspaceId)).isEmpty();
        assertThat(notion.calls()).isZero();
    }

    @Test
    @DisplayName("state가 만료됐거나 연결을 시작한 Member가 이제 멤버가 아니면 FAILED로 보낸다")
    void callback_expiredStateOrLeftMember() throws Exception {
        // given
        String expired = state(startAuthorization(memberId));
        String left = state(startAuthorization(otherId));
        jdbc.sql("UPDATE search_notion_authorization_states SET expires_at = :past WHERE member_id = :memberId")
                .param(
                        "past",
                        Timestamp.from(
                                Instant.now()
                                        .minusSeconds(1)
                        )
                )
                .param(
                        "memberId",
                        memberId
                )
                .update();
        jdbc.sql("UPDATE workspace_members SET left_at = :now WHERE member_id = :memberId")
                .param(
                        "now",
                        Timestamp.from(Instant.now())
                )
                .param(
                        "memberId",
                        otherId
                )
                .update();

        // when & then
        callback(
                Map.of(
                        "state",
                        expired,
                        "code",
                        "code-a"
                ),
                "FAILED"
        );
        callback(
                Map.of(
                        "state",
                        left,
                        "code",
                        "code-b"
                ),
                "FAILED"
        );
        assertThat(connections.findByWorkspaceId(workspaceId)).isEmpty();
        assertThat(notion.calls()).isZero();
    }

    @Test
    @DisplayName("동의 화면에 다녀온 사이 다른 멤버가 연결을 마쳤으면 code를 바꾸지 않고 앞선 연결을 둔다")
    void callback_connectedMeanwhile() throws Exception {
        // given
        String late = state(startAuthorization(otherId));
        callback(
                Map.of(
                        "state",
                        state(startAuthorization(memberId)),
                        "code",
                        "code-a"
                ),
                "CONNECTED"
        );

        // when & then
        callback(
                Map.of(
                        "state",
                        late,
                        "code",
                        "code-b"
                ),
                "NOTION_CONNECTION_ALREADY_CONNECTED"
        );
        assertThat(connectedMember()).isEqualTo(memberId);
        assertThat(notion.calls()).isEqualTo(1);
    }

    @Test
    @DisplayName("만료된 연결을 다시 연결하면 연결한 사람과 노션 워크스페이스가 다시 연결한 쪽으로 바뀐다")
    void callback_reconnect() throws Exception {
        // given
        saveConnection(
                memberId,
                true
        );

        // when
        callback(
                Map.of(
                        "state",
                        state(startAuthorization(otherId)),
                        "code",
                        "code-b"
                ),
                "CONNECTED"
        );

        // then
        mvc.perform(
                get(
                        PATH,
                        workspaceId
                ).cookie(cookie(otherId))
        )
                .andExpect(jsonPath("$.status").value("CONNECTED"))
                .andExpect(jsonPath("$.notionWorkspaceName").value("노션 code-b"))
                .andExpect(jsonPath("$.connectedBy.memberId").value(otherId))
                .andExpect(jsonPath("$.connectedBy.nickname").value("흑곰"));
        assertThat(
                connections.findByWorkspaceId(workspaceId)
                        .orElseThrow()
                        .isSameNotionWorkspace("ws-code-b")
        ).isTrue();
    }

    @Test
    @DisplayName("토큰 교환이 실패하면 FAILED로 보내고 만료된 연결과 연결한 사람을 바꾸지 않는다")
    void callback_exchangeFailed() throws Exception {
        // given
        saveConnection(
                memberId,
                true
        );
        notion.fail();

        // when
        callback(
                Map.of(
                        "state",
                        state(startAuthorization(otherId)),
                        "code",
                        "code-b"
                ),
                "FAILED"
        );

        // then
        NotionConnection connection = connections.findByWorkspaceId(workspaceId)
                .orElseThrow();
        assertThat(connection.isConnected()).isFalse();
        assertThat(connection.isConnectedBy(memberId)).isTrue();
    }

    @Test
    @DisplayName("두 콜백이 동시에 새 연결을 만들면 연결은 한 개만 생기고 다른 쪽은 ALREADY_CONNECTED다")
    void callback_concurrentConnect() throws Exception {
        // given
        String first = state(startAuthorization(memberId));
        String second = state(startAuthorization(otherId));
        notion.awaitTogether(2);

        // when
        CompletableFuture<NotionConnectionOutcome> one = CompletableFuture.supplyAsync(
                () -> complete(
                        first,
                        "code-a"
                )
        );
        CompletableFuture<NotionConnectionOutcome> two = CompletableFuture.supplyAsync(
                () -> complete(
                        second,
                        "code-b"
                )
        );

        // then
        assertThat(
                List.of(
                        one.get(
                                20,
                                TimeUnit.SECONDS
                        ),
                        two.get(
                                20,
                                TimeUnit.SECONDS
                        )
                )
        ).containsExactlyInAnyOrder(
                NotionConnectionOutcome.CONNECTED,
                NotionConnectionOutcome.NOTION_CONNECTION_ALREADY_CONNECTED
        );
        assertThat(
                jdbc.sql("SELECT COUNT(*) FROM search_notion_connections")
                        .query(Long.class)
                        .single()
        ).isOne();
    }

    @Test
    @DisplayName("연결한 사람과 OWNER는 끊을 수 있고 만료된 연결도 끊는다")
    void disconnect_success() throws Exception {
        // given
        saveConnection(
                memberId,
                false
        );

        // when & then
        mvc.perform(
                disconnect(
                        memberId,
                        csrf()
                )
        )
                .andExpect(status().isNoContent());
        assertThat(connections.findByWorkspaceId(workspaceId)).isEmpty();
        saveConnection(
                memberId,
                true
        );
        mvc.perform(
                disconnect(
                        ownerId,
                        csrf()
                )
        )
                .andExpect(status().isNoContent());
        assertThat(connections.findByWorkspaceId(workspaceId)).isEmpty();
    }

    @Test
    @DisplayName("연결한 사람도 OWNER도 아니면 403, 연결이 없으면 404로 거절한다")
    void disconnect_failure() throws Exception {
        // when & then
        mvc.perform(
                disconnect(
                        memberId,
                        csrf()
                )
        )
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOTION_CONNECTION_NOT_FOUND"));
        saveConnection(
                memberId,
                false
        );
        mvc.perform(
                disconnect(
                        otherId,
                        csrf()
                )
        )
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("NOTION_CONNECTION_DISCONNECT_DENIED"));
        assertThat(connectedMember()).isEqualTo(memberId);
    }

    @Test
    @DisplayName("연결한 사람이 나가면 연결을 지우고 다른 멤버가 나가면 지우지 않는다")
    void leave_connectedMember() throws Exception {
        // given
        saveConnection(
                memberId,
                false
        );

        // when & then
        leave(otherId);
        assertThat(connectedMember()).isEqualTo(memberId);
        leave(memberId);
        assertThat(connections.findByWorkspaceId(workspaceId)).isEmpty();
    }

    @Test
    @DisplayName("OWNER가 넘기고 나가도 연결한 사람이 남아 있으면 연결을 지우지 않는다")
    void transfer_keepsConnection() throws Exception {
        // given
        saveConnection(
                memberId,
                false
        );

        // when
        transfer(
                ownerId,
                memberId
        );

        // then
        assertThat(connectedMember()).isEqualTo(memberId);
    }

    @Test
    @DisplayName("연결한 OWNER가 넘기고 나가면 연결을 지운다")
    void transfer_connectedOwner() throws Exception {
        // given
        saveConnection(
                ownerId,
                false
        );

        // when
        transfer(
                ownerId,
                memberId
        );

        // then
        assertThat(connections.findByWorkspaceId(workspaceId)).isEmpty();
    }

    @Test
    @DisplayName("연결한 사람이 마지막 멤버로 나가면 Workspace 삭제와 함께 연결을 지운다")
    void leave_lastMember() throws Exception {
        // given
        leave(memberId);
        leave(otherId);
        saveConnection(
                ownerId,
                true
        );

        // when
        leave(ownerId);

        // then
        assertThat(connections.findByWorkspaceId(workspaceId)).isEmpty();
    }

    @Test
    @DisplayName("OpenAPI에 노션 연결 API와 콜백의 응답 코드를 문서화한다")
    void openApi() throws Exception {
        // when
        JsonNode paths = json.readTree(
                mvc.perform(get("/v3/api-docs"))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString()
        )
                .path("paths");

        // then
        assertThat(
                responseCodes(
                        paths.path(PATH)
                                .path("get")
                )
        ).contains(
                "200",
                "401",
                "403"
        );
        assertThat(
                responseCodes(
                        paths.path(PATH)
                                .path("delete")
                )
        ).contains(
                "204",
                "401",
                "403",
                "404"
        );
        assertThat(
                responseCodes(
                        paths.path(PATH + "/authorization")
                                .path("post")
                )
        ).contains(
                "200",
                "400",
                "401",
                "403",
                "409"
        );
        assertThat(
                responseCodes(
                        paths.path(CALLBACK)
                                .path("get")
                )
        ).contains("302");
    }

    private List<String> responseCodes(JsonNode operation) {
        return operation.path("responses")
                .propertyStream()
                .map(Map.Entry::getKey)
                .toList();
    }

    private NotionConnectionOutcome complete(
            String state,
            String code
    ) {
        return callbacks.complete(
                new NotionConnectionCallback(
                        state,
                        code,
                        null,
                        null,
                        state
                )
        )
                .outcome();
    }

    private void callback(
            Map<String, String> params,
            String expected
    ) throws Exception {
        MockHttpServletRequestBuilder request = get(CALLBACK);
        params.forEach(request::param);
        if (params.containsKey("state")) {
            request.cookie(
                    new Cookie(
                            SearchNotionConnectionController.STATE_COOKIE,
                            params.get("state")
                    )
            );
        }
        mvc.perform(request)
                .andExpect(status().isFound())
                .andExpect(
                        header().string(
                                "Location",
                                "/workspace/" + workspaceId + "/apps?notionConnection=" + expected
                        )
                );
    }

    private String startAuthorization(long member) throws Exception {
        MvcResult result = mvc.perform(
                authorization(
                        member,
                        "{\"agreed\":true}",
                        csrf()
                )
        )
                .andExpect(status().isOk())
                .andReturn();
        return json.readTree(
                result.getResponse()
                        .getContentAsString()
        )
                .path("authorizationUrl")
                .asString();
    }

    private MockHttpServletRequestBuilder authorization(
            long member,
            String body,
            Csrf csrf
    ) {
        return post(
                PATH + "/authorization",
                workspaceId
        ).contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .cookie(
                        cookie(member),
                        csrf.cookie()
                )
                .header(
                        "X-XSRF-TOKEN",
                        csrf.token()
                );
    }

    private MockHttpServletRequestBuilder disconnect(
            long member,
            Csrf csrf
    ) {
        return delete(
                PATH,
                workspaceId
        ).cookie(
                cookie(member),
                csrf.cookie()
        )
                .header(
                        "X-XSRF-TOKEN",
                        csrf.token()
                );
    }

    private void leave(long member) throws Exception {
        Csrf csrf = csrf();
        mvc.perform(
                delete(
                        "/api/v1/workspaces/{workspaceId}/members/me",
                        workspaceId
                ).cookie(
                        cookie(member),
                        csrf.cookie()
                )
                        .header(
                                "X-XSRF-TOKEN",
                                csrf.token()
                        )
        )
                .andExpect(status().isNoContent());
    }

    private void transfer(
            long owner,
            long successor
    ) throws Exception {
        Csrf csrf = csrf();
        ResultActions result = mvc.perform(
                post(
                        "/api/v1/workspaces/{workspaceId}/ownership-transfers",
                        workspaceId
                ).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"successorMemberId\":" + successor + "}")
                        .cookie(
                                cookie(owner),
                                csrf.cookie()
                        )
                        .header(
                                "X-XSRF-TOKEN",
                                csrf.token()
                        )
        );
        result.andExpect(status().isNoContent());
    }

    private Csrf csrf() throws Exception {
        MvcResult result = mvc.perform(get("/api/v1/auth/csrf"))
                .andExpect(status().isOk())
                .andReturn();
        return new Csrf(
                result.getResponse()
                        .getCookie("XSRF-TOKEN"),
                json.readTree(
                        result.getResponse()
                                .getContentAsString()
                )
                        .path("token")
                        .asString()
        );
    }

    private Cookie cookie(long member) {
        return new Cookie(
                "KNOT_ACCESS_TOKEN",
                tokens.issue(
                        AuthenticatedMember.of(
                                member,
                                "멤버",
                                null
                        )
                )
        );
    }

    private static String challenge(String verifier) throws java.security.NoSuchAlgorithmException {
        return java.util.Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(
                        java.security.MessageDigest.getInstance("SHA-256")
                                .digest(verifier.getBytes(java.nio.charset.StandardCharsets.US_ASCII))
                );
    }

    private static String state(String authorizationUrl) {
        return query(URI.create(authorizationUrl)).get("state");
    }

    private static Map<String, String> query(URI uri) {
        return Arrays.stream(
                uri.getRawQuery()
                        .split("&")
        )
                .map(
                        pair -> pair.split(
                                "=",
                                2
                        )
                )
                .collect(
                        Collectors.toMap(
                                pair -> pair[0],
                                pair -> java.net.URLDecoder.decode(
                                        pair[1],
                                        java.nio.charset.StandardCharsets.UTF_8
                                )
                        )
                );
    }

    private void saveConnection(
            long connector,
            boolean expired
    ) {
        NotionConnection connection = NotionConnection.connect(
                workspaceId,
                connector,
                new NotionConnectionGrant(
                        "ws-a",
                        "Knot 팀",
                        "🪢",
                        cipher.encrypt("access-a"),
                        cipher.encrypt("refresh-a"),
                        NOW.plusSeconds(3600)
                ),
                NOW
        );
        if (expired) {
            connection.expire(NOW);
        }
        connections.save(connection);
    }

    private long connectedMember() {
        return jdbc.sql("SELECT connected_member_id FROM search_notion_connections WHERE workspace_id = :workspaceId")
                .param(
                        "workspaceId",
                        workspaceId
                )
                .query(Long.class)
                .single();
    }

    private long stateCount() {
        return jdbc.sql("SELECT COUNT(*) FROM search_notion_authorization_states")
                .query(Long.class)
                .single();
    }

    private long saveWorkspace() {
        return jdbc.sql("INSERT INTO workspaces (name, created_at) VALUES ('노션 팀', :time) RETURNING id")
                .param(
                        "time",
                        Timestamp.from(NOW)
                )
                .query(Long.class)
                .single();
    }

    private long saveMember(String nickname) {
        return jdbc.sql("INSERT INTO members (nickname) VALUES (:nickname) RETURNING id")
                .param(
                        "nickname",
                        nickname
                )
                .query(Long.class)
                .single();
    }

    private void join(
            long member,
            String role
    ) {
        jdbc.sql("""
                INSERT INTO workspace_members (workspace_id, member_id, role, joined_at)
                VALUES (:workspaceId, :memberId, :role, :time)
                """)
                .param(
                        "workspaceId",
                        workspaceId
                )
                .param(
                        "memberId",
                        member
                )
                .param(
                        "role",
                        role
                )
                .param(
                        "time",
                        Timestamp.from(NOW)
                )
                .update();
    }

    private record Csrf(
            Cookie cookie,
            String token
    ) {
    }

    @TestConfiguration
    static class FakeNotionConfiguration {

        @Bean
        @Primary
        FakeAuthorizationServer fakeAuthorizationServer() {
            return new FakeAuthorizationServer();
        }
    }

    /**
     * 노션 토큰 엔드포인트 대역. code마다 다른 노션 워크스페이스를 준다.
     */
    static class FakeAuthorizationServer implements SearchNotionAuthorizationServer {

        private final AtomicInteger calls = new AtomicInteger();
        private final List<String> verifiers = new java.util.concurrent.CopyOnWriteArrayList<>();
        private volatile Function<String, SearchNotionIssuedGrant> issue;
        private volatile CountDownLatch together;

        void reset() {
            calls.set(0);
            verifiers.clear();
            together = null;
            issue = code -> new SearchNotionIssuedGrant(
                    "access-" + code,
                    "refresh-" + code,
                    Instant.now()
                            .plusSeconds(28800),
                    "ws-" + code,
                    "노션 " + code,
                    null
            );
        }

        void fail() {
            issue = code -> {
                throw new SearchNotionException(SearchNotionFailure.FAILED);
            };
        }

        void awaitTogether(int count) {
            together = new CountDownLatch(count);
        }

        int calls() {
            return calls.get();
        }

        List<String> verifiers() {
            return verifiers;
        }

        @Override
        public SearchNotionIssuedGrant exchange(
                String code,
                String codeVerifier
        ) {
            calls.incrementAndGet();
            verifiers.add(codeVerifier);
            awaitOthers();
            return issue.apply(code);
        }

        private void awaitOthers() {
            CountDownLatch latch = together;
            if (latch == null) {
                return;
            }
            latch.countDown();
            try {
                latch.await(
                        10,
                        TimeUnit.SECONDS
                );
            } catch (InterruptedException exception) {
                Thread.currentThread()
                        .interrupt();
            }
        }
    }
}
