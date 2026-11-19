package com.knot.backend.search.infrastructure.notion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.knot.backend.global.config.SearchNotionProperties;
import com.knot.backend.search.application.notion.SearchNotionFailure;
import com.knot.backend.search.application.notion.SearchNotionIssuedGrant;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

class NotionOAuthClientTest {
    private static final String REDIRECT_URI = "https://api.knoted.kr/api/v1/search/notion-connection/callback";

    private final ObjectMapper mapper = JsonMapper.builder()
            .build();
    private final Clock clock = Clock.systemUTC();
    private final SearchNotionProperties properties = new SearchNotionProperties();
    private FakeNotionServer notion;
    private NotionOAuthClient client;

    @BeforeEach
    void setUp() throws Exception {
        notion = new FakeNotionServer(mapper);
        properties.setMcpUrl(
                notion.mcpUrl()
                        .toString()
        );
        properties.setTokenEndpoint(
                notion.tokenUrl()
                        .toString()
        );
        properties.setClientId("knot-client");
        properties.setRedirectUri(REDIRECT_URI);
        NotionHttp http = new NotionHttp(clock);
        NotionMcpTransport transport = new NotionMcpTransport(
                http,
                mapper,
                properties
        );
        client = new NotionOAuthClient(
                http,
                transport,
                new NotionToolResults(mapper),
                mapper,
                properties,
                clock
        );
    }

    @AfterEach
    void tearDown() {
        notion.close();
    }

    @Test
    @DisplayName("code와 PKCE verifier, resource를 보내고 교환 응답의 노션 워크스페이스를 함께 받는다")
    void exchange_success() {
        // given
        notion.tokenResponse(
                FakeNotionServer.Response.json(
                        200,
                        """
                                {"access_token":"access-1","refresh_token":"refresh-1","expires_in":28800,"token_type":"bearer",
                                "workspace_id":"ws-1","workspace_name":"Knot 팀","workspace_icon":"🪢","bot_id":"b","unknown":1}
                                """
                )
        );

        // when
        SearchNotionIssuedGrant grant = client.exchange(
                "code-1",
                "verifier-1"
        );

        // then
        assertThat(grant.accessToken()).isEqualTo("access-1");
        assertThat(grant.refreshToken()).isEqualTo("refresh-1");
        assertThat(grant.notionWorkspaceId()).isEqualTo("ws-1");
        assertThat(grant.notionWorkspaceName()).isEqualTo("Knot 팀");
        assertThat(grant.notionWorkspaceIcon()).isEqualTo("🪢");
        assertThat(grant.accessTokenExpiresAt()).isAfter(
                Instant.now()
                        .plusSeconds(28000)
        );
        assertThat(grant.toString()).doesNotContain(
                "access-1",
                "refresh-1"
        );
        assertThat(notion.tokenRequests()).singleElement()
                .satisfies(
                        form -> assertThat(form).contains(
                                "grant_type=authorization_code",
                                "code=code-1",
                                "code_verifier=verifier-1",
                                "redirect_uri=" + encode(REDIRECT_URI),
                                "resource=" + encode(
                                        notion.mcpUrl()
                                                .toString()
                                ),
                                "client_id=knot-client"
                        )
                );
        assertThat(notion.toolCalls()).isEmpty();
    }

    @Test
    @DisplayName("교환 응답에 노션 워크스페이스 ID가 없으면 notion-get-self로 채운다")
    void exchange_selfWorkspace() {
        // given
        notion.acceptToken("access-1");
        notion.tokenResponse(
                FakeNotionServer.Response.json(
                        200,
                        "{\"access_token\":\"access-1\",\"refresh_token\":\"refresh-1\",\"expires_in\":28800}"
                )
        );
        notion.tools(
                params -> FakeNotionServer.Response.tool(
                        FakeNotionServer.textResult(
                                "{\"workspace\":{\"id\":\"ws-self\",\"name\":\"자기 팀\"},\"user\":{\"type\":\"person\",\"id\":\"u\"}}",
                                false
                        )
                )
        );

        // when
        SearchNotionIssuedGrant grant = client.exchange(
                "code-1",
                "verifier-1"
        );

        // then
        assertThat(notion.toolCalls()).containsExactly("notion-get-self");
        assertThat(grant.notionWorkspaceId()).isEqualTo("ws-self");
        assertThat(grant.notionWorkspaceName()).isEqualTo("자기 팀");
        assertThat(grant.notionWorkspaceIcon()).isNull();
    }

    @Test
    @DisplayName("client-secret이 있으면 Basic 인증으로 보내고 본문에 client_id를 싣지 않는다")
    void exchange_confidentialClient() {
        // given
        properties.setClientSecret("secret-1");
        notion.tokenResponse(
                FakeNotionServer.Response.json(
                        200,
                        "{\"access_token\":\"access-1\",\"workspace_id\":\"ws-1\"}"
                )
        );

        // when
        client.exchange(
                "code-1",
                "verifier-1"
        );

        // then
        assertThat(notion.authorizations()).containsExactly(
                "Basic " + Base64.getEncoder()
                        .encodeToString("knot-client:secret-1".getBytes(StandardCharsets.UTF_8))
        );
        assertThat(
                notion.tokenRequests()
                        .getFirst()
        ).doesNotContain("client_id");
    }

    @Test
    @DisplayName("토큰 교환이 거절되면 실패로 알리고 응답 본문을 메시지에 싣지 않는다")
    void exchange_rejected() {
        // given
        notion.tokenResponse(
                FakeNotionServer.Response.json(
                        400,
                        "{\"error\":\"invalid_grant\",\"error_description\":\"비공개 설명\"}"
                )
        );

        // when & then
        assertThatThrownBy(
                () -> client.exchange(
                        "code-1",
                        "verifier-1"
                )
        ).hasMessageNotContaining("비공개")
                .extracting("failure")
                .isEqualTo(SearchNotionFailure.FAILED);
    }

    private static String encode(String value) {
        return URLEncoder.encode(
                value,
                StandardCharsets.UTF_8
        );
    }
}
