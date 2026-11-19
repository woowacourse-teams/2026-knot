package com.knot.backend.search.application;

import com.knot.backend.search.application.dto.result.SearchUsePlan;

public interface SearchIntentClassifier {

    SearchUsePlan classify(
            SearchToolScope scope,
            String question
    );
}
