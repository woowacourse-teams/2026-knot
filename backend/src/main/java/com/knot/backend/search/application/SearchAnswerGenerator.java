package com.knot.backend.search.application;

import com.knot.backend.search.application.dto.result.SearchDocumentResult;
import com.knot.backend.search.application.dto.result.SearchRetrievalResult;
import java.util.List;
import java.util.function.Consumer;

public interface SearchAnswerGenerator {

    List<Long> generate(
            SearchToolScope scope,
            String question,
            List<SearchDocumentResult> documents,
            Consumer<String> onDelta
    );

    default List<Long> generateFromRetrieval(
            SearchToolScope scope,
            String question,
            SearchRetrievalResult retrieval,
            Consumer<String> onDelta
    ) {
        return generate(
                scope,
                question,
                retrieval.documents(),
                onDelta
        );
    }
}
