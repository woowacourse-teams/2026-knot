package com.knot.backend.search.infrastructure.notion;

import com.knot.backend.testsupport.LlmHttpServer;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 노션 MCP(/mcp)와 토큰 엔드포인트(/token)를 흉내 내는 로컬 HTTP 대역. 실제 노션은 부르지 않는다.
 */
final class FakeNotionServer implements AutoCloseable {
    static final String SESSION = "session-1";

    private final LlmHttpServer server;
    private final ObjectMapper mapper;
    private final List<String> toolCalls = new CopyOnWriteArrayList<>();
    private final List<String> sessions = new CopyOnWriteArrayList<>();
    private final List<String> authorizations = new CopyOnWriteArrayList<>();
    private final List<String> tokenRequests = new CopyOnWriteArrayList<>();
    private final List<JsonNode> toolArguments = new CopyOnWriteArrayList<>();
    private volatile String acceptedToken = "access-old";
    private volatile boolean eventStream;
    private volatile Function<JsonNode, Response> tools = params -> Response.json(
            200,
            "{}"
    );
    private volatile Response token = Response.json(
            200,
            "{}"
    );

    FakeNotionServer(ObjectMapper mapper) throws IOException {
        this.mapper = mapper;
        server = new LlmHttpServer();
        server.respond(
                "/mcp",
                this::mcp
        );
        server.respond(
                "/token",
                exchange -> {
                    tokenRequests.add(server.requestBody());
                    authorizations.add(String.valueOf(server.authorization()));
                    send(
                            exchange,
                            token
                    );
                }
        );
    }

    URI mcpUrl() {
        return server.baseUrl()
                .resolve("/mcp");
    }

    URI tokenUrl() {
        return server.baseUrl()
                .resolve("/token");
    }

    void acceptToken(String accessToken) {
        acceptedToken = accessToken;
    }

    void useEventStream() {
        eventStream = true;
    }

    void tools(Function<JsonNode, Response> responder) {
        tools = responder;
    }

    void tokenResponse(Response response) {
        token = response;
    }

    int requestCount() {
        return server.requestCount();
    }

    List<String> toolCalls() {
        return toolCalls;
    }

    List<JsonNode> toolArguments() {
        return toolArguments;
    }

    List<String> sessions() {
        return sessions;
    }

    List<String> authorizations() {
        return authorizations;
    }

    List<String> tokenRequests() {
        return tokenRequests;
    }

    private void mcp(HttpExchange exchange) throws IOException {
        authorizations.add(String.valueOf(server.authorization()));
        if (!("Bearer " + acceptedToken).equals(server.authorization())) {
            send(
                    exchange,
                    Response.json(
                            401,
                            ""
                    )
            );
            return;
        }
        JsonNode message = mapper.readTree(server.requestBody());
        String method = message.path("method")
                .asString("");
        if ("initialize".equals(method)) {
            exchange.getResponseHeaders()
                    .add(
                            "Mcp-Session-Id",
                            SESSION
                    );
            send(
                    exchange,
                    rpc(
                            message,
                            "{\"protocolVersion\":\"2025-06-18\",\"capabilities\":{},\"serverInfo\":{\"name\":\"fake\"}}"
                    )
            );
            return;
        }
        sessions.add(
                String.valueOf(
                        exchange.getRequestHeaders()
                                .getFirst("Mcp-Session-Id")
                )
        );
        if ("notifications/initialized".equals(method)) {
            send(
                    exchange,
                    Response.json(
                            202,
                            ""
                    )
            );
            return;
        }
        JsonNode params = message.path("params");
        toolCalls.add(
                params.path("name")
                        .asString()
        );
        toolArguments.add(params.path("arguments"));
        Response response = tools.apply(params);
        if (response.status() != 200) {
            send(
                    exchange,
                    response
            );
            return;
        }
        send(
                exchange,
                rpc(
                        message,
                        response.body()
                )
        );
    }

    private Response rpc(
            JsonNode message,
            String result
    ) {
        String body = "{\"jsonrpc\":\"2.0\",\"id\":" + message.path("id")
                .asLong() + ",\"result\":" + result + "}";
        if (!eventStream) {
            return Response.json(
                    200,
                    body
            );
        }
        return new Response(
                200,
                "event: message\ndata: {\"jsonrpc\":\"2.0\",\"method\":\"notifications/message\"}\n\nevent: message\ndata: "
                        + body + "\n\n",
                Map.of(
                        "Content-Type",
                        "text/event-stream"
                )
        );
    }

    private static void send(
            HttpExchange exchange,
            Response response
    ) throws IOException {
        response.headers()
                .forEach(
                        (
                                name,
                                value
                        ) -> exchange.getResponseHeaders()
                                .add(
                                        name,
                                        value
                                )
                );
        byte[] bytes = response.body()
                .getBytes(StandardCharsets.UTF_8);
        if (bytes.length == 0) {
            exchange.sendResponseHeaders(
                    response.status(),
                    -1
            );
            return;
        }
        exchange.sendResponseHeaders(
                response.status(),
                bytes.length
        );
        exchange.getResponseBody()
                .write(bytes);
    }

    /**
     * 도구 결과 text 블록 하나를 담은 tools/call result.
     */
    static String textResult(
            String text,
            boolean error
    ) {
        return "{\"content\":[{\"type\":\"text\",\"text\":" + quote(text) + "}],\"isError\":" + error + "}";
    }

    private static String quote(String text) {
        return "\"" + text.replace(
                "\\",
                "\\\\"
        )
                .replace(
                        "\"",
                        "\\\""
                )
                .replace(
                        "\n",
                        "\\n"
                ) + "\"";
    }

    @Override
    public void close() {
        server.close();
    }

    record Response(
            int status,
            String body,
            Map<String, String> headers
    ) {

        static Response json(
                int status,
                String body
        ) {
            return new Response(
                    status,
                    body,
                    Map.of(
                            "Content-Type",
                            "application/json"
                    )
            );
        }

        static Response tool(String result) {
            return json(
                    200,
                    result
            );
        }
    }
}
