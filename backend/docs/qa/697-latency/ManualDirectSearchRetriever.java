package com.knot.backend.search.qa;

import com.knot.backend.search.application.SearchDocumentRetriever;
import com.knot.backend.search.application.SearchDocumentToolService;
import com.knot.backend.search.application.SearchToolScope;
import com.knot.backend.search.application.dto.query.SearchDocumentParameters;
import com.knot.backend.search.application.dto.result.SearchDocumentCard;
import com.knot.backend.search.application.dto.result.SearchDocumentPage;
import com.knot.backend.search.application.dto.result.SearchDocumentResult;
import com.knot.backend.search.application.dto.result.SearchDocumentSlice;
import com.knot.backend.search.application.dto.result.SearchRetrievalResult;
import com.knot.backend.search.domain.SearchToolException;
import java.util.List;

class ManualDirectSearchRetriever implements SearchDocumentRetriever {
    private static final int MAX_READ_PAGES = 10;

    private final SearchDocumentToolService tools;

    ManualDirectSearchRetriever(SearchDocumentToolService tools) {
        this.tools = tools;
    }

    @Override
    public SearchRetrievalResult retrieve(
            SearchToolScope scope,
            String question
    ) {
        SearchDocumentPage page = tools.search(
                scope,
                new SearchDocumentParameters(
                        question,
                        null,
                        null,
                        null,
                        null,
                        3
                )
        );
        List<SearchDocumentResult> documents = page.items()
                .stream()
                .map(card -> read(scope, card))
                .toList();
        return new SearchRetrievalResult(
                documents,
                0
        );
    }

    private SearchDocumentResult read(
            SearchToolScope scope,
            SearchDocumentCard card
    ) {
        SearchDocumentSlice slice = tools.read(
                scope,
                card.documentId(),
                null
        );
        StringBuilder content = new StringBuilder(slice.content());
        int pages = 1;
        while (slice.truncated() && pages < MAX_READ_PAGES) {
            slice = tools.read(
                    scope,
                    card.documentId(),
                    slice.nextCursor()
            );
            content.append(slice.content());
            pages++;
        }
        if (slice.truncated()) {
            throw new SearchToolException("INCOMPLETE_DOCUMENT_EXPLORATION");
        }
        return new SearchDocumentResult(
                card.documentId(),
                card.title(),
                card.topic(),
                content.toString()
        );
    }
}
