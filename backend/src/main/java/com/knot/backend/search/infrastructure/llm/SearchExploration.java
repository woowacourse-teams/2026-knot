package com.knot.backend.search.infrastructure.llm;

import com.knot.backend.search.application.dto.result.SearchDocumentResult;
import com.knot.backend.search.domain.SearchToolException;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;

public class SearchExploration {

    private final Map<Long, SearchDocumentResult> read = new LinkedHashMap<>();
    private final Map<Long, String> revisions = new LinkedHashMap<>();
    private boolean queried;
    private boolean found;
    private boolean searched;
    private boolean searchMatchesFound;

    public void record(
            String name,
            JsonNode result
    ) {
        if ("read_document".equals(name)) {
            recordRead(result);
            return;
        }
        queried = true;
        JsonNode items = result.path("items");
        if (!items.isArray()) {
            throw new SearchToolException("INVALID_MCP_RESPONSE");
        }
        found |= !items.isEmpty();
        if ("search_documents".equals(name)) {
            searched = true;
            searchMatchesFound |= !items.isEmpty();
        }
    }

    public List<SearchDocumentResult> finish() {
        if (!read.isEmpty()) {
            return List.copyOf(read.values());
        }
        if (!queried || searchMatchesFound) {
            throw new SearchToolException("INCOMPLETE_DOCUMENT_EXPLORATION");
        }
        if (!searched && found) {
            throw new SearchToolException("INCOMPLETE_DOCUMENT_EXPLORATION");
        }
        return List.of();
    }

    private void recordRead(JsonNode result) {
        JsonNode document = result.path("document");
        long id = document.path("documentId")
                .asLong();
        String content = result.path("content")
                .asString();
        String revision = document.path("contentRevision")
                .asString();
        int offset = result.path("offset")
                .asInt(-1);
        if (id <= 0 || content == null || revision == null || offset < 0) {
            throw new SearchToolException("INVALID_MCP_RESPONSE");
        }
        String previous = previous(
                id,
                revision,
                offset
        );
        read.put(
                id,
                new SearchDocumentResult(
                        id,
                        document.path("title")
                                .asString(),
                        document.path("topic")
                                .asString(),
                        previous + content,
                        date(
                                document,
                                "recordingStartedAt"
                        ),
                        date(
                                document,
                                "createdAt"
                        )
                )
        );
        revisions.put(
                id,
                revision
        );
    }

    private Instant date(
            JsonNode document,
            String field
    ) {
        String value = document.path(field)
                .asString();
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException exception) {
            throw new SearchToolException(
                    "INVALID_MCP_RESPONSE",
                    exception
            );
        }
    }

    private String previous(
            long id,
            String revision,
            int offset
    ) {
        SearchDocumentResult existing = read.get(id);
        if (existing == null) {
            validateFirstOffset(offset);
            return "";
        }
        if (!revision.equals(revisions.get(id)) || existing.content()
                .codePointCount(
                        0,
                        existing.content()
                                .length()
                ) != offset) {
            throw new SearchToolException("INCOMPLETE_DOCUMENT_EXPLORATION");
        }
        return existing.content();
    }
    private void validateFirstOffset(int offset) {
        if (offset != 0) {
            throw new SearchToolException("INCOMPLETE_DOCUMENT_EXPLORATION");
        }
    }
}
