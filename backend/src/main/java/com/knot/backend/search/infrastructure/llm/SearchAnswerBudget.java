package com.knot.backend.search.infrastructure.llm;

import com.knot.backend.search.application.dto.result.SearchDocumentResult;
import com.knot.backend.search.application.dto.result.SearchRetrievalResult.Item;
import com.knot.backend.search.domain.SearchToolException;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import tools.jackson.databind.ObjectMapper;

public class SearchAnswerBudget {
    private static final String PARTIAL_INSTRUCTION = "\n일부 문서 또는 본문이 제외되었을 수 있습니다. 제공된 근거 범위만 설명하고 전체 회의를 확인했다고 주장하지 마세요.";

    private final ObjectMapper mapper;
    private final int limit;
    private final String question;
    private final Instant requestedAt;
    private final boolean fullDocumentsOnly;
    private final List<Item> items;
    private final List<SearchDocumentResult> selected = new ArrayList<>();
    private final List<Map<String, Object>> history = new ArrayList<>();
    private boolean documentsLimited;
    private boolean historyLimited;

    public SearchAnswerBudget(
            ObjectMapper mapper,
            int limit,
            String question
    ) {
        this(
                mapper,
                limit,
                question,
                null
        );
    }

    public SearchAnswerBudget(
            ObjectMapper mapper,
            int limit,
            String question,
            Instant requestedAt
    ) {
        this(
                mapper,
                limit,
                question,
                requestedAt,
                false,
                List.of()
        );
    }

    SearchAnswerBudget(
            ObjectMapper mapper,
            int limit,
            String question,
            Instant requestedAt,
            boolean fullDocumentsOnly,
            List<Item> items
    ) {
        this.mapper = mapper;
        this.limit = limit;
        this.question = question;
        this.requestedAt = requestedAt;
        this.fullDocumentsOnly = fullDocumentsOnly;
        this.items = List.copyOf(items);
    }

    public void select(
            List<SearchDocumentResult> documents,
            List<Map<String, Object>> previous
    ) {
        if (!fits(limit)) {
            throw new SearchToolException("SEARCH_CONTEXT_LIMIT_EXCEEDED");
        }
        int documentLimit = limit - limit / 4;
        for (SearchDocumentResult document : documents) {
            addDocument(
                    document,
                    documentLimit
            );
        }
        if (!documents.isEmpty() && selected.isEmpty()) {
            throw new SearchToolException("SEARCH_CONTEXT_LIMIT_EXCEEDED");
        }
        documentsLimited = documentsLimited || selected.size() < documents.size();
        for (int index = previous.size() - 1; index >= 0; index--) {
            history.addFirst(previous.get(index));
            if (!fits(limit)) {
                history.removeFirst();
                break;
            }
        }
        historyLimited = history.size() < previous.size();
        if (!fits(limit)) {
            throw new SearchToolException("SEARCH_CONTEXT_LIMIT_EXCEEDED");
        }
    }

    private void addDocument(
            SearchDocumentResult document,
            int documentLimit
    ) {
        selected.add(document);
        if (fits(documentLimit)) {
            return;
        }
        selected.removeLast();
        if (fullDocumentsOnly || !selected.isEmpty()) {
            return;
        }
        addPartialDocument(
                document,
                documentLimit
        );
        if (selected.isEmpty()) {
            addPartialDocument(
                    document,
                    limit
            );
        }
    }

    private void addPartialDocument(
            SearchDocumentResult document,
            int documentLimit
    ) {
        int low = 0;
        String content = document.content();
        int high = content.codePointCount(
                0,
                content.length()
        );
        while (low < high) {
            int middle = (low + high + 1) / 2;
            selected.add(
                    partialDocument(
                            document,
                            middle
                    )
            );
            boolean fits = fits(documentLimit);
            selected.removeLast();
            if (fits) {
                low = middle;
                continue;
            }
            high = middle - 1;
        }
        if (low == 0) {
            return;
        }
        selected.add(
                partialDocument(
                        document,
                        low
                )
        );
        documentsLimited = true;
    }

    private SearchDocumentResult partialDocument(
            SearchDocumentResult document,
            int codePoints
    ) {
        String content = document.content();
        int end = content.offsetByCodePoints(
                0,
                codePoints
        );
        return new SearchDocumentResult(
                document.documentId(),
                document.title(),
                document.topic(),
                content.substring(
                        0,
                        end
                ) + "\n[본문 일부만 제공됨]",
                document.recordingStartedAt(),
                document.createdAt()
        );
    }

    private boolean fits(int maximum) {
        return mapper.writeValueAsString(messages(true))
                .length() <= maximum;
    }

    public List<Map<String, Object>> messages() {
        return messages(documentsLimited);
    }

    private List<Map<String, Object>> messages(boolean isPartial) {
        String system = SearchAnswerPrompt.system();
        if (requestedAt != null) {
            system += "\nrequestedAt은 질문 시각, recordingStartedAt은 회의 녹음 시작 시각, documentCreatedAt은 문서 생성 시각입니다. 날짜는 서울 시간입니다. 회의 날짜와 문서 생성 날짜를 구별하세요.";
        }
        if (isPartial) {
            system += PARTIAL_INSTRUCTION;
        }
        if (!items.isEmpty()) {
            system += "\nsearchItems는 요청의 등장 순서별 검색 범위입니다. 각 항목은 같은 항목의 evidenceIds에서만 확인하세요. "
                    + "근거 ID가 비어 있으면 해당 항목의 회의 사실을 단정하지 말고 제공된 범위만 답하세요.";
        }
        List<Map<String, Object>> evidence = selected.stream()
                .map(this::evidence)
                .toList();
        Map<String, Object> user = new LinkedHashMap<>();
        user.put(
                "question",
                question
        );
        user.put(
                "conversationHistory",
                history
        );
        user.put(
                "evidence",
                evidence
        );
        if (!items.isEmpty()) {
            user.put(
                    "searchItems",
                    itemEvidence()
            );
        }
        if (requestedAt != null) {
            user.put(
                    "requestedAt",
                    localTime(requestedAt)
            );
        }
        return List.of(
                Map.of(
                        "role",
                        "system",
                        "content",
                        system
                ),
                Map.of(
                        "role",
                        "user",
                        "content",
                        mapper.writeValueAsString(user)
                )
        );
    }

    private List<Map<String, Object>> itemEvidence() {
        Set<Long> included = selected.stream()
                .map(SearchDocumentResult::documentId)
                .collect(Collectors.toSet());
        return items.stream()
                .map(
                        item -> Map.<String, Object>of(
                                "ordinal",
                                item.ordinal(),
                                "evidenceIds",
                                item.documentIds()
                                        .stream()
                                        .filter(included::contains)
                                        .map(id -> "E" + id)
                                        .toList()
                        )
                )
                .toList();
    }

    private Map<String, Object> evidence(SearchDocumentResult document) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put(
                "evidenceId",
                "E" + document.documentId()
        );
        result.put(
                "documentId",
                document.documentId()
        );
        result.put(
                "title",
                document.title()
        );
        result.put(
                "topic",
                document.topic()
        );
        result.put(
                "content",
                document.content()
        );
        if (document.recordingStartedAt() != null) {
            result.put(
                    "recordingStartedAt",
                    localTime(document.recordingStartedAt())
            );
        }
        if (document.createdAt() != null) {
            result.put(
                    "documentCreatedAt",
                    localTime(document.createdAt())
            );
        }
        return result;
    }

    private String localTime(Instant time) {
        return time.atZone(ZoneId.of("Asia/Seoul"))
                .toOffsetDateTime()
                .toString();
    }

    public List<SearchDocumentResult> documents() {
        return List.copyOf(selected);
    }

    public int historyCount() {
        return history.size();
    }

    public boolean partial() {
        return documentsLimited || historyLimited;
    }

    boolean documentsLimited() {
        return documentsLimited;
    }
}
