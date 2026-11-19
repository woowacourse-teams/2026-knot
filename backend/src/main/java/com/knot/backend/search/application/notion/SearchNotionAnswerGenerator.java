package com.knot.backend.search.application.notion;

import com.knot.backend.search.application.SearchToolScope;
import com.knot.backend.search.application.dto.result.SearchRetrievalResult;
import java.util.List;
import java.util.function.Consumer;

public interface SearchNotionAnswerGenerator {

    /**
     * 회의 문서와 읽은 노션 페이지를 출처와 함께 넘겨 답글을 받는다. draftRequested면 같은 호출에서 노션 페이지 만들기 초안이나
     * 초안을 쓰지 못한 사유도 받는다(SEARCH-R35, R46).
     */
    SearchNotionAnswer generate(
            SearchToolScope scope,
            String question,
            SearchRetrievalResult retrieval,
            List<SearchNotionPage> pages,
            boolean draftRequested,
            Consumer<String> onDelta
    );
}
