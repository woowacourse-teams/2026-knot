package com.knot.backend.search.infrastructure.notion;

import com.knot.backend.global.config.SearchNotionProperties;
import com.knot.backend.search.application.notion.SearchNotionException;
import com.knot.backend.search.application.notion.SearchNotionFailure;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 노션 MCP Streamable HTTP 클라이언트. 호출마다 initialize, notifications/initialized,
 * tools/call을 보내고 서버가 준 Mcp-Session-Id를 되돌려 보낸다. 응답은 application/json과
 * text/event-stream 모두 읽는다.
 */
@Component
@RequiredArgsConstructor
class NotionMcpTransport {
    static final Set<String> ALLOWED_TOOLS = Set.of(
            "notion-search",
            "notion-fetch",
            "notion-create-pages",
            "notion-get-self"
    );
    private static final String PROTOCOL_VERSION = "2025-06-18";
    private static final String SESSION_HEADER = "Mcp-Session-Id";
    private static final long INITIALIZE_ID = 1;
    private static final long CALL_ID = 2;

    private final NotionHttp http;
    private final ObjectMapper mapper;
    private final SearchNotionProperties properties;

    /**
     * 허용한 도구만 부르고 tools/call의 result를 돌려준다. 도구 결과의 isError는 호출한 쪽이 해석한다.
     */
    JsonNode callTool(
            String accessToken,
            String tool,
            Map<String, Object> arguments,
            Instant deadline
    ) {
        if (!ALLOWED_TOOLS.contains(tool)) {
            throw new SearchNotionException(SearchNotionFailure.FAILED);
        }
        HttpResponse<String> initialized = post(
                accessToken,
                null,
                Map.of(
                        "jsonrpc",
                        "2.0",
                        "id",
                        INITIALIZE_ID,
                        "method",
                        "initialize",
                        "params",
                        Map.of(
                                "protocolVersion",
                                PROTOCOL_VERSION,
                                "capabilities",
                                Map.of(),
                                "clientInfo",
                                Map.of(
                                        "name",
                                        "knot-search",
                                        "version",
                                        "1.0.0"
                                )
                        )
                ),
                deadline
        );
        result(
                initialized,
                INITIALIZE_ID
        );
        String session = initialized.headers()
                .firstValue(SESSION_HEADER)
                .orElse(null);
        post(
                accessToken,
                session,
                Map.of(
                        "jsonrpc",
                        "2.0",
                        "method",
                        "notifications/initialized"
                ),
                deadline
        );
        return result(
                post(
                        accessToken,
                        session,
                        Map.of(
                                "jsonrpc",
                                "2.0",
                                "id",
                                CALL_ID,
                                "method",
                                "tools/call",
                                "params",
                                Map.of(
                                        "name",
                                        tool,
                                        "arguments",
                                        arguments
                                )
                        ),
                        deadline
                ),
                CALL_ID
        );
    }

    private HttpResponse<String> post(
            String accessToken,
            String session,
            Map<String, Object> message,
            Instant deadline
    ) {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(properties.getMcpUrl()))
                .header(
                        "Authorization",
                        "Bearer " + accessToken
                )
                .header(
                        "Content-Type",
                        "application/json"
                )
                .header(
                        "Accept",
                        "application/json, text/event-stream"
                )
                .header(
                        "MCP-Protocol-Version",
                        PROTOCOL_VERSION
                )
                .POST(HttpRequest.BodyPublishers.ofByteArray(mapper.writeValueAsBytes(message)));
        if (session != null) {
            request.header(
                    SESSION_HEADER,
                    session
            );
        }
        HttpResponse<String> response = http.send(
                request,
                deadline
        );
        validateStatus(response);
        return response;
    }

    private void validateStatus(HttpResponse<String> response) {
        int status = response.statusCode();
        if (status == 401) {
            throw new NotionMcpUnauthorizedException();
        }
        if (status == 429) {
            throw new SearchNotionException(
                    SearchNotionFailure.RATE_LIMITED,
                    retryAfter(response),
                    null
            );
        }
        if (status < 200 || status >= 300) {
            throw new SearchNotionException(SearchNotionFailure.FAILED);
        }
    }

    private JsonNode result(
            HttpResponse<String> response,
            long id
    ) {
        JsonNode message = message(
                response,
                id
        );
        JsonNode result = message.path("result");
        if (message.has("error") || !result.isObject()) {
            throw new SearchNotionException(SearchNotionFailure.FAILED);
        }
        return result;
    }

    private JsonNode message(
            HttpResponse<String> response,
            long id
    ) {
        boolean eventStream = response.headers()
                .firstValue("Content-Type")
                .filter(type -> type.startsWith("text/event-stream"))
                .isPresent();
        if (eventStream) {
            return eventMessage(
                    response.body(),
                    id
            );
        }
        return parse(response.body());
    }

    /**
     * SSE 본문에서 요청 id와 같은 JSON-RPC 응답을 찾는다. 서버가 먼저 보내는 알림 이벤트는 건너뛴다.
     */
    private JsonNode eventMessage(
            String body,
            long id
    ) {
        StringBuilder data = new StringBuilder();
        for (String line : (body + "\n\n").split(
                "\r?\n",
                -1
        )) {
            if (line.startsWith("data:")) {
                data.append(
                        line.substring(5)
                                .strip()
                )
                        .append('\n');
                continue;
            }
            if (!line.isEmpty() || data.isEmpty()) {
                continue;
            }
            JsonNode message = parse(data.toString());
            data.setLength(0);
            if (message.path("id")
                    .asLong(-1) == id) {
                return message;
            }
        }
        throw new SearchNotionException(SearchNotionFailure.FAILED);
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

    private static Duration retryAfter(HttpResponse<String> response) {
        Optional<String> header = response.headers()
                .firstValue("Retry-After");
        if (header.isEmpty() || !header.get()
                .strip()
                .matches("\\d{1,6}")) {
            return null;
        }
        return Duration.ofSeconds(
                Long.parseLong(
                        header.get()
                                .strip()
                )
        );
    }
}
