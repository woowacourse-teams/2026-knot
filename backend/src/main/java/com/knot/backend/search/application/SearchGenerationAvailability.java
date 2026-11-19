package com.knot.backend.search.application;

import com.knot.backend.global.config.SearchWorkerProperties;
import com.knot.backend.search.domain.SearchErrorCode;
import com.knot.backend.search.domain.SearchException;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class SearchGenerationAvailability {

    private final SearchWorkerProperties properties;
    private final ObjectProvider<SearchDocumentRetriever> retrievers;
    private final ObjectProvider<SearchAnswerGenerator> generators;
    private final ObjectProvider<SearchIntentClassifier> classifiers;

    public void requireAvailable() {
        requireWorkerAvailable();
        if (classifiers.getIfAvailable() == null) {
            throw new SearchException(SearchErrorCode.ANSWER_GENERATION_UNAVAILABLE);
        }
        if (retrievers.getIfAvailable() == null || generators.getIfAvailable() == null) {
            throw new SearchException(SearchErrorCode.ANSWER_GENERATION_UNAVAILABLE);
        }
    }

    public void requireWorkerAvailable() {
        if (!properties.isEnabled()) {
            throw new SearchException(SearchErrorCode.ANSWER_GENERATION_UNAVAILABLE);
        }
    }
}
