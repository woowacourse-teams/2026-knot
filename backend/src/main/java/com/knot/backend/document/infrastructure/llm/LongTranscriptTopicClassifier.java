package com.knot.backend.document.infrastructure.llm;

import com.knot.backend.document.domain.DocumentErrorCode;
import com.knot.backend.document.domain.DocumentException;
import com.knot.backend.document.domain.model.DocumentTopic;
import com.knot.backend.global.exception.LlmErrorCode;
import com.knot.backend.global.exception.LlmException;
import com.knot.backend.global.infrastructure.llm.LlmClient;
import com.knot.backend.global.infrastructure.llm.LlmCompletionRequest;
import com.knot.backend.global.infrastructure.llm.LlmGenerationOptions;
import com.knot.backend.global.infrastructure.llm.LlmMessage;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonParser;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

final class LongTranscriptTopicClassifier {
    private static final int CHUNK_CHARACTERS = 4000;
    private static final int MIN_CHUNK_CHARACTERS = 256;
    private static final int MAX_REQUESTS = 80;
    private static final int MAX_CANDIDATES = 200;
    private static final String SYSTEM = """
            회의 구간에서 찾아낸 안건 후보를 통합한다. candidates는 입력 자료다.
            같은 안건만 묶고 같은 단어가 있어도 다른 안건은 합치지 않는다.
            하나의 기능이나 업무에 대한 기본 규칙, 세부 조건, 예외와 실패 처리는 독립 안건이 아니라 함께 묶는다.
            한 문제를 해결하기 위한 구현 방식과 선택지는 같은 안건으로 묶는다.
            서로 다른 목적이나 다른 업무는 같은 기능 이름이나 단어가 있어도 분리한다.
            예를 들어 결제 요청 실패의 원인과 실패 재시도는 함께 묶지만 결제 실패와 결제 수단 선정은 분리한다.
            같은 작업이 중단된 후 재개하는 방법과 중단 시 기존 데이터를 보존하는 정책은 하나의 장애 대응 안건이다.
            예를 들어 메시지 전송 중 끊김의 재전송과 실패 시 메시지 보존은 함께 묶는다.
            후보의 표현이 달라도 먼저 그 후보가 다루는 작업을 식별한다.
            같은 작업이 실패했을 때의 재개와 해당 작업의 기존 데이터 보존은 반드시 한 그룹으로 묶는다.
            다만 작업 대상이나 해결할 목적이 다른 후보까지 포괄적인 단어만으로 묶지는 않는다.
            모든 후보 id를 정확히 한 번 포함한다. 각 묶음의 name은 그 묶음의 기존 후보 이름 중 하나를 그대로 사용한다.
            새로운 안건이나 사실을 만들지 않는다. 조건이나 제안의 범위를 보존한다.
            """;
    private static final String SCHEMA = """
            {"type":"object","properties":{"groups":{"type":"array","items":{
              "type":"object","properties":{"name":{"type":"string"},"ids":{"type":"array","items":{"type":"integer"}}},
              "required":["name","ids"],"additionalProperties":false}}},
             "required":["groups"],"additionalProperties":false}
            """;

    private final LlmClient client;
    private final DocumentTopicPrompt prompt;
    private final ObjectMapper mapper;
    private final Function<String, List<DocumentTopic>> parser;
    private final TranscriptChunker chunker = new TranscriptChunker();

    LongTranscriptTopicClassifier(
            LlmClient client,
            DocumentTopicPrompt prompt,
            ObjectMapper mapper,
            Function<String, List<DocumentTopic>> parser
    ) {
        this.client = client;
        this.prompt = prompt;
        this.mapper = mapper;
        this.parser = parser;
    }

    List<DocumentTopic> classify(String transcript) {
        List<DocumentTopic> found = new ArrayList<>();
        int[] requests = {0};
        for (String chunk : chunker.split(
                transcript,
                CHUNK_CHARACTERS
        )) {
            classifyChunk(
                    chunk,
                    found,
                    requests
            );
        }
        List<DocumentTopic> candidates = List.copyOf(new LinkedHashSet<>(found));
        if (candidates.size() < 2) {
            return candidates;
        }
        if (candidates.size() > MAX_CANDIDATES) {
            throw new LlmException(LlmErrorCode.LLM_INPUT_LIMIT_EXCEEDED);
        }
        return merge(candidates);
    }

    private void classifyChunk(
            String chunk,
            List<DocumentTopic> found,
            int[] requests
    ) {
        if (++requests[0] > MAX_REQUESTS) {
            throw new LlmException(LlmErrorCode.LLM_INPUT_LIMIT_EXCEEDED);
        }
        try {
            found.addAll(parser.apply(client.complete(prompt.createRequest(chunk))));
        } catch (LlmException exception) {
            if (exception.getErrorCode() != LlmErrorCode.LLM_INPUT_LIMIT_EXCEEDED
                    || chunk.length() <= MIN_CHUNK_CHARACTERS) {
                throw exception;
            }
            for (String part : chunker.split(
                    chunk,
                    chunk.length() / 2
            )) {
                classifyChunk(
                        part,
                        found,
                        requests
                );
            }
        }
    }

    private List<DocumentTopic> merge(List<DocumentTopic> candidates) {
        ObjectNode input = mapper.createObjectNode();
        ArrayNode items = input.putArray("candidates");
        for (int id = 0; id < candidates.size(); id++) {
            ObjectNode item = items.addObject();
            item.put(
                    "id",
                    id
            );
            item.put(
                    "name",
                    candidates.get(id)
                            .value()
            );
        }
        LlmCompletionRequest request = new LlmCompletionRequest(
                List.of(
                        new LlmMessage(
                                "system",
                                SYSTEM
                        ),
                        new LlmMessage(
                                "user",
                                mapper.writeValueAsString(input)
                        )
                ),
                "document_topic_merge",
                mapper.readTree(SCHEMA),
                new LlmGenerationOptions(
                        0.2,
                        0.9,
                        20,
                        1.0,
                        false,
                        null,
                        4096
                )
        );
        return parseGroups(
                client.complete(request),
                candidates
        );
    }

    private List<DocumentTopic> parseGroups(
            String response,
            List<DocumentTopic> candidates
    ) {
        try (JsonParser reader = mapper.createParser(response)) {
            JsonNode root = mapper.readTree(reader);
            if (reader.nextToken() != null || root == null || !root.isObject() || root.size() != 1
                    || !root.path("groups")
                            .isArray()) {
                throw invalid();
            }
            Set<Integer> used = new HashSet<>();
            List<Group> groups = new ArrayList<>();
            for (JsonNode group : root.path("groups")) {
                groups.add(
                        readGroup(
                                group,
                                candidates,
                                used
                        )
                );
            }
            if (used.size() != candidates.size()) {
                throw invalid();
            }
            groups.sort(Comparator.comparingInt(Group::first));
            LinkedHashSet<DocumentTopic> topics = new LinkedHashSet<>();
            for (Group group : groups) {
                topics.add(group.name());
            }
            return List.copyOf(topics);
        } catch (JacksonException exception) {
            throw new DocumentException(
                    DocumentErrorCode.INVALID_TOPIC_CLASSIFICATION_RESPONSE,
                    exception
            );
        }
    }

    private Group readGroup(
            JsonNode group,
            List<DocumentTopic> candidates,
            Set<Integer> used
    ) {
        if (!group.isObject() || group.size() != 2 || !group.path("name")
                .isString() || !group.path("ids")
                        .isArray()
                || group.path("ids")
                        .isEmpty()) {
            throw invalid();
        }
        DocumentTopic name;
        try {
            name = DocumentTopic.of(
                    group.path("name")
                            .asString()
            );
        } catch (DocumentException exception) {
            throw invalid();
        }
        int first = candidates.size();
        boolean containsName = false;
        for (JsonNode node : group.path("ids")) {
            if (!node.isInt()) {
                throw invalid();
            }
            int id = node.asInt();
            if (id < 0 || id >= candidates.size() || !used.add(id)) {
                throw invalid();
            }
            first = Math.min(
                    first,
                    id
            );
            containsName |= candidates.get(id)
                    .equals(name);
        }
        if (!containsName) {
            throw invalid();
        }
        return new Group(
                first,
                name
        );
    }

    private DocumentException invalid() {
        return new DocumentException(DocumentErrorCode.INVALID_TOPIC_CLASSIFICATION_RESPONSE);
    }

    private record Group(
            int first,
            DocumentTopic name
    ) {
    }
}
