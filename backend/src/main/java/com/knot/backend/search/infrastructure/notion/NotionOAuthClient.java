package com.knot.backend.search.infrastructure.notion;

import com.knot.backend.global.config.SearchNotionProperties;
import com.knot.backend.search.application.notion.SearchNotionAuthorizationServer;
import com.knot.backend.search.application.notion.SearchNotionException;
import com.knot.backend.search.application.notion.SearchNotionFailure;
import com.knot.backend.search.application.notion.SearchNotionIssuedGrant;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 노션 MCP 토큰 엔드포인트. code 교환과 refresh 모두 resource를 보낸다. client-secret이 있으면
 * client_secret_basic, 없으면 공개 클라이언트로 client_id만 보낸다.
 */
@Component
@RequiredArgsConstructor
class NotionOAuthClient implements SearchNotionAuthorizationServer {
    private static final Duration EXCHANGE_TIMEOUT = Duration.ofSeconds(10);

    private final NotionHttp http;
    private final NotionMcpTransport transport;
    private final NotionToolResults results;
    private final ObjectMapper mapper;
    private final SearchNotionProperties properties;
    private final Clock clock;

    @Override
    public SearchNotionIssuedGrant exchange(
            String code,
            String codeVerifier
    ) {
        Instant deadline = clock.instant()
                .plus(EXCHANGE_TIMEOUT);
        Map<String, String> form = new LinkedHashMap<>();
        form.put(
                "grant_type",
                "authorization_code"
        );
        form.put(
                "code",
                code
        );
        form.put(
                "redirect_uri",
                properties.getRedirectUri()
        );
        form.put(
                "code_verifier",
                codeVerifier
        );
        HttpResponse<String> response = token(
                form,
                deadline
        );
        if (response.statusCode() != 200) {
            throw new SearchNotionException(SearchNotionFailure.FAILED);
        }
        JsonNode body = parse(response.body());
        SearchNotionIssuedGrant grant = grant(body);
        if (grant.notionWorkspaceId() != null) {
            return grant;
        }
        return withSelfWorkspace(
                grant,
                deadline
        );
    }

    /**
     * 회전한 새 토큰을 돌려준다. 노션이 grant를 거절하면(HTTP 400, invalid_grant) 비어 있고, 호출한 쪽이 연결을
     * 만료로 바꾼다.
     */
    Optional<SearchNotionIssuedGrant> refresh(
            String refreshToken,
            Instant deadline
    ) {
        Map<String, String> form = new LinkedHashMap<>();
        form.put(
                "grant_type",
                "refresh_token"
        );
        form.put(
                "refresh_token",
                refreshToken
        );
        HttpResponse<String> response = token(
                form,
                deadline
        );
        if (isRevoked(response)) {
            return Optional.empty();
        }
        if (response.statusCode() == 429) {
            throw new SearchNotionException(SearchNotionFailure.RATE_LIMITED);
        }
        if (response.statusCode() != 200) {
            throw new SearchNotionException(SearchNotionFailure.FAILED);
        }
        return Optional.of(grant(parse(response.body())));
    }

    private HttpResponse<String> token(
            Map<String, String> form,
            Instant deadline
    ) {
        form.put(
                "resource",
                properties.getMcpUrl()
        );
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(properties.getTokenEndpoint()))
                .header(
                        "Content-Type",
                        "application/x-www-form-urlencoded"
                )
                .header(
                        "Accept",
                        "application/json"
                );
        authenticate(
                request,
                form
        );
        return http.send(
                request.POST(HttpRequest.BodyPublishers.ofString(encode(form))),
                deadline
        );
    }

    private void authenticate(
            HttpRequest.Builder request,
            Map<String, String> form
    ) {
        String secret = properties.getClientSecret();
        if (secret == null || secret.isBlank()) {
            form.put(
                    "client_id",
                    properties.getClientId()
            );
            return;
        }
        String credentials = urlEncode(properties.getClientId()) + ":" + urlEncode(secret);
        request.header(
                "Authorization",
                "Basic " + Base64.getEncoder()
                        .encodeToString(credentials.getBytes(StandardCharsets.UTF_8))
        );
    }

    /**
     * invalid_grant만 grant 취소로 본다. invalid_client 같은 설정 오류로 모든 연결을 만료로 바꾸지 않는다.
     */
    private boolean isRevoked(HttpResponse<String> response) {
        return "invalid_grant".equals(
                parseQuietly(response.body()).path("error")
                        .asString("")
        );
    }

    private SearchNotionIssuedGrant grant(JsonNode body) {
        String accessToken = body.path("access_token")
                .asString("");
        if (accessToken.isBlank()) {
            throw new SearchNotionException(SearchNotionFailure.FAILED);
        }
        return new SearchNotionIssuedGrant(
                accessToken,
                nullableText(body.path("refresh_token")),
                expiresAt(body.path("expires_in")),
                nullableText(body.path("workspace_id")),
                nullableText(body.path("workspace_name")),
                nullableText(body.path("workspace_icon"))
        );
    }

    /**
     * 교환 응답에 workspace_id가 없으면 notion-get-self로 노션 워크스페이스 ID와 이름을 채운다.
     */
    private SearchNotionIssuedGrant withSelfWorkspace(
            SearchNotionIssuedGrant grant,
            Instant deadline
    ) {
        try {
            NotionToolResults.NotionSelfWorkspace workspace = results
                    .selfWorkspace(
                            results.requireSuccess(
                                    transport.callTool(
                                            grant.accessToken(),
                                            "notion-get-self",
                                            Map.of(),
                                            deadline
                                    ),
                                    NotionToolAccess.READ
                            )
                    )
                    .orElseThrow(() -> new SearchNotionException(SearchNotionFailure.FAILED));
            return new SearchNotionIssuedGrant(
                    grant.accessToken(),
                    grant.refreshToken(),
                    grant.accessTokenExpiresAt(),
                    workspace.id(),
                    workspace.name(),
                    grant.notionWorkspaceIcon()
            );
        } catch (NotionMcpUnauthorizedException exception) {
            throw new SearchNotionException(
                    SearchNotionFailure.FAILED,
                    null,
                    exception
            );
        }
    }

    private Instant expiresAt(JsonNode expiresIn) {
        if (!expiresIn.isNumber()) {
            return null;
        }
        return clock.instant()
                .plusSeconds(expiresIn.asLong());
    }

    private JsonNode parse(String body) {
        try {
            return mapper.readTree(body);
        } catch (JacksonException exception) {
            throw new SearchNotionException(
                    SearchNotionFailure.FAILED,
                    null,
                    exception
            );
        }
    }

    private JsonNode parseQuietly(String body) {
        try {
            return mapper.readTree(body);
        } catch (JacksonException exception) {
            return mapper.createObjectNode();
        }
    }

    private static String nullableText(JsonNode node) {
        if (!node.isString() || node.stringValue()
                .isBlank()) {
            return null;
        }
        return node.stringValue();
    }

    private static String encode(Map<String, String> form) {
        return form.entrySet()
                .stream()
                .map(entry -> urlEncode(entry.getKey()) + "=" + urlEncode(entry.getValue()))
                .collect(Collectors.joining("&"));
    }

    private static String urlEncode(String value) {
        return URLEncoder.encode(
                value,
                StandardCharsets.UTF_8
        );
    }
}
