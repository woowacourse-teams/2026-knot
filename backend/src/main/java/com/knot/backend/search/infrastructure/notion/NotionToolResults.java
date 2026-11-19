package com.knot.backend.search.infrastructure.notion;

import com.knot.backend.search.application.notion.SearchNotionCreatedPage;
import com.knot.backend.search.application.notion.SearchNotionDatabase;
import com.knot.backend.search.application.notion.SearchNotionException;
import com.knot.backend.search.application.notion.SearchNotionFailure;
import com.knot.backend.search.application.notion.SearchNotionObjectKind;
import com.knot.backend.search.application.notion.SearchNotionPage;
import com.knot.backend.search.application.notion.SearchNotionSearchHit;
import com.knot.backend.search.domain.model.notion.SearchWriteCardDatabaseFields;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.MissingNode;

/**
 * 노션 MCP 도구 결과를 읽는다. 결과 JSON은 structuredContent 또는 text 블록에 온다. 일반 연결의 필드 이름은
 * 노션이 문서로 확정하지 않아 흔한 이름을 함께 읽는다.
 */
@Component
@RequiredArgsConstructor
class NotionToolResults {
    static final String DATA_SOURCE_PREFIX = "collection://";
    private static final Pattern DATA_SOURCE_URL = Pattern.compile("collection://[A-Za-z0-9-]+");
    private static final Pattern DATA_SOURCE_STATE = Pattern.compile(
            "<data-source-state>(.*?)</data-source-state>",
            Pattern.DOTALL
    );
    private static final List<String> KNOWN_ERRORS = List.of(
            "rate_limited",
            "restricted_resource",
            "object_not_found",
            "validation_error"
    );

    private final ObjectMapper mapper;

    /**
     * 도구 결과가 isError면 사유로 바꿔 던진다.
     */
    JsonNode requireSuccess(
            JsonNode result,
            NotionToolAccess access
    ) {
        if (!result.path("isError")
                .asBoolean(false)) {
            return result;
        }
        JsonNode payload = payload(result);
        String code = errorCode(
                payload,
                contentText(result)
        );
        if ("rate_limited".equals(code)) {
            throw new SearchNotionException(
                    SearchNotionFailure.RATE_LIMITED,
                    retryAfter(payload),
                    null
            );
        }
        throw new SearchNotionException(access.failure(code));
    }

    List<SearchNotionSearchHit> searchHits(JsonNode result) {
        JsonNode results = payload(result).path("results");
        if (!results.isArray()) {
            throw new SearchNotionException(SearchNotionFailure.FAILED);
        }
        return results.valueStream()
                .map(this::searchHit)
                .flatMap(Optional::stream)
                .toList();
    }

    SearchNotionPage page(
            String pageId,
            JsonNode result
    ) {
        JsonNode payload = payload(result);
        if (!payload.isObject()) {
            return new SearchNotionPage(
                    pageId,
                    "",
                    "",
                    contentText(result)
            );
        }
        return new SearchNotionPage(
                pageId,
                text(payload.path("title")),
                text(payload.path("url")),
                firstText(
                        payload.path("text"),
                        payload.path("content"),
                        payload.path("markdown")
                )
        );
    }

    /**
     * DB를 fetch한 결과에서 첫 데이터 소스 주소(collection://)를 찾는다.
     */
    Optional<String> dataSourceUrl(JsonNode result) {
        Matcher matcher = DATA_SOURCE_URL.matcher(allText(result));
        if (!matcher.find()) {
            return Optional.empty();
        }
        return Optional.of(matcher.group());
    }

    /**
     * 데이터 소스를 fetch한 결과에서 속성 이름과 형식을 읽는다. 스키마는 data-source-state 태그 안 JSON이나 결과
     * JSON의 schema에서 찾는다.
     */
    SearchNotionDatabase database(
            String dataSourceUrl,
            JsonNode result
    ) {
        JsonNode state = dataSourceState(result);
        JsonNode schema = state.path("schema");
        if (!schema.isObject()) {
            throw new SearchNotionException(SearchNotionFailure.FAILED);
        }
        List<SearchWriteCardDatabaseFields.Property> properties = schema.propertyStream()
                .map(this::property)
                .flatMap(Optional::stream)
                .toList();
        return new SearchNotionDatabase(
                dataSourceUrl,
                firstText(
                        state.path("name"),
                        state.path("title"),
                        payload(result).path("title")
                ),
                "",
                properties
        );
    }

    SearchNotionCreatedPage createdPage(JsonNode result) {
        JsonNode page = payload(result).path("pages")
                .path(0);
        String id = text(page.path("id"));
        String url = text(page.path("url"));
        if (id.isBlank() || url.isBlank()) {
            throw new SearchNotionException(SearchNotionFailure.FAILED);
        }
        return new SearchNotionCreatedPage(
                id,
                url
        );
    }

    /**
     * notion-get-self 결과의 노션 워크스페이스 ID와 이름. 아이콘은 notion-get-self가 주지 않는다.
     */
    Optional<NotionSelfWorkspace> selfWorkspace(JsonNode result) {
        JsonNode workspace = payload(result).path("workspace");
        String id = text(workspace.path("id"));
        if (id.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(
                new NotionSelfWorkspace(
                        id,
                        blankToNull(text(workspace.path("name")))
                )
        );
    }

    private Optional<SearchNotionSearchHit> searchHit(JsonNode item) {
        String id = text(item.path("id"));
        Optional<SearchNotionObjectKind> kind = kind(
                firstText(
                        item.path("type"),
                        item.path("object")
                )
        );
        if (id.isBlank() || kind.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(
                new SearchNotionSearchHit(
                        id,
                        text(item.path("title")),
                        text(item.path("url")),
                        kind.get(),
                        path(item.path("path")),
                        text(item.path("highlight"))
                )
        );
    }

    private Optional<SearchNotionObjectKind> kind(String type) {
        if ("page".equals(type)) {
            return Optional.of(SearchNotionObjectKind.PAGE);
        }
        if ("database".equals(type) || "data_source".equals(type)) {
            return Optional.of(SearchNotionObjectKind.DATABASE);
        }
        return Optional.empty();
    }

    /**
     * 위치 경로가 문자열이면 문자열 값을 쓰고, 배열이면 항목(문자열 또는 title)을 「 / 」로 잇는다. 다른 모양은 빈 문자열이다.
     */
    private String path(JsonNode path) {
        if (path.isString()) {
            return path.stringValue();
        }
        return path.valueStream()
                .map(
                        part -> firstText(
                                part,
                                part.path("title")
                        )
                )
                .filter(part -> !part.isBlank())
                .collect(Collectors.joining(" / "));
    }

    private Optional<SearchWriteCardDatabaseFields.Property> property(Map.Entry<String, JsonNode> entry) {
        String type = text(
                entry.getValue()
                        .path("type")
        );
        if (type.isBlank()) {
            return Optional.empty();
        }
        String name = firstText(
                entry.getValue()
                        .path("name")
        );
        if (name.isBlank()) {
            name = entry.getKey();
        }
        return Optional.of(
                new SearchWriteCardDatabaseFields.Property(
                        name,
                        type
                )
        );
    }

    private JsonNode dataSourceState(JsonNode result) {
        JsonNode payload = payload(result);
        if (payload.path("schema")
                .isObject()) {
            return payload;
        }
        Matcher matcher = DATA_SOURCE_STATE.matcher(allText(result));
        while (matcher.find()) {
            JsonNode state = parseObject(matcher.group(1));
            if (state.path("schema")
                    .isObject()) {
                return state;
            }
        }
        return MissingNode.getInstance();
    }

    private String errorCode(
            JsonNode payload,
            String text
    ) {
        String code = firstText(
                payload.at("/error/code"),
                payload.path("code")
        );
        if (!code.isBlank()) {
            return code;
        }
        return KNOWN_ERRORS.stream()
                .filter(text::contains)
                .findFirst()
                .orElse("");
    }

    private Duration retryAfter(JsonNode payload) {
        return Stream.of(
                payload.at("/error/retry_after_seconds"),
                payload.path("retry_after_seconds"),
                payload.at("/additional_data/retry_after")
        )
                .filter(JsonNode::isNumber)
                .map(JsonNode::asLong)
                .filter(seconds -> seconds >= 0)
                .findFirst()
                .map(Duration::ofSeconds)
                .orElse(null);
    }

    /**
     * 결과 JSON. structuredContent가 객체면 structuredContent를 쓰고, 아니면 text 블록을 JSON으로
     * 읽는다. 둘 다 아니면 MissingNode다.
     */
    private JsonNode payload(JsonNode result) {
        JsonNode structured = result.path("structuredContent");
        if (structured.isObject()) {
            return structured;
        }
        return parseObject(contentText(result));
    }

    private String allText(JsonNode result) {
        return contentText(result) + "\n" + payload(result).toString();
    }

    private String contentText(JsonNode result) {
        List<String> texts = new ArrayList<>();
        for (JsonNode content : result.path("content")) {
            if ("text".equals(text(content.path("type")))) {
                texts.add(text(content.path("text")));
            }
        }
        return String.join(
                "\n",
                texts
        );
    }

    private JsonNode parseObject(String text) {
        try {
            JsonNode node = mapper.readTree(text);
            if (node.isObject()) {
                return node;
            }
            return MissingNode.getInstance();
        } catch (JacksonException exception) {
            return MissingNode.getInstance();
        }
    }

    private static String text(JsonNode node) {
        return firstText(node);
    }

    private static String firstText(JsonNode... nodes) {
        return Stream.of(nodes)
                .filter(JsonNode::isString)
                .map(JsonNode::stringValue)
                .filter(value -> !value.isBlank())
                .findFirst()
                .orElse("");
    }

    private static String blankToNull(String value) {
        if (value.isBlank()) {
            return null;
        }
        return value;
    }

    record NotionSelfWorkspace(
            String id,
            String name
    ) {
    }
}
