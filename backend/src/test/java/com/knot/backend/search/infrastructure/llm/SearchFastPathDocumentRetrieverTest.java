package com.knot.backend.search.infrastructure.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.knot.backend.document.domain.model.DocumentStatus;
import com.knot.backend.search.application.SearchDocumentQuery;
import com.knot.backend.search.application.SearchToolAccess;
import com.knot.backend.search.application.SearchToolScope;
import com.knot.backend.search.application.dto.query.SearchDocumentParameters;
import com.knot.backend.search.application.dto.result.SearchDocumentRecord;
import com.knot.backend.search.application.dto.result.SearchRetrievalResult;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class SearchFastPathDocumentRetrieverTest {

    private final SearchDocumentQuery query = mock(SearchDocumentQuery.class);
    private final SearchToolAccess access = mock(SearchToolAccess.class);
    private final SearchQueryNormalizer normalizer = new SearchQueryNormalizer();
    private final SearchFastPathDocumentRetriever retriever = new SearchFastPathDocumentRetriever(
            query,
            access,
            normalizer
    );
    private final SearchToolScope scope = new SearchToolScope(
            31,
            2,
            5,
            6,
            7,
            UUID.randomUUID(),
            Instant.parse("2026-10-18T00:00:00Z"),
            Instant.parse("2026-10-18T00:02:00Z")
    );

    @Test
    @DisplayName("Fast Path는 정규화한 검색어로 Workspace 문서를 찾는다")
    void retrieve_success_normalizesQueryAndMapsDocuments() {
        // given
        SearchDocumentRecord document = document(
                41,
                "운영 로그 보관 기준"
        );
        when(
                query.searchPage(
                        eq(scope),
                        any(SearchDocumentParameters.class),
                        isNull()
                )
        ).thenReturn(List.of(document));

        // when
        SearchRetrievalResult result = retriever.retrieve(
                scope,
                "운영 로그는 며칠 보관하기로 기록돼 있나?"
        );

        // then
        ArgumentCaptor<SearchDocumentParameters> parameters = ArgumentCaptor.forClass(SearchDocumentParameters.class);
        verify(access).validate(scope);
        verify(query).searchPage(
                eq(scope),
                parameters.capture(),
                isNull()
        );
        assertThat(
                parameters.getValue()
                        .query()
        ).isEqualTo("운영 로그 며칠 보관 기록");
        assertThat(
                parameters.getValue()
                        .size()
        ).isEqualTo(20);
        assertThat(result.documents()).containsExactly(
                new com.knot.backend.search.application.dto.result.SearchDocumentResult(
                        41,
                        "운영 로그 보관 기준",
                        "운영",
                        "문서 본문",
                        document.recordingStartedAt(),
                        document.createdAt()
                )
        );
        assertThat(result.excludedCount()).isZero();
    }

    @Test
    @DisplayName("검색어가 질문 표현뿐이면 문서 쿼리를 실행하지 않는다")
    void retrieve_success_skipsEmptyNormalizedQuery() {
        // given
        String question = "누구인가요?";

        // when
        SearchRetrievalResult result = retriever.retrieve(
                scope,
                question
        );

        // then
        assertThat(result.documents()).isEmpty();
        assertThat(result.excludedCount()).isZero();
        verify(access).validate(scope);
        verifyNoInteractions(query);
    }

    @Test
    @DisplayName("날짜만 있는 질문은 회의 시작 날짜로 목록을 조회한다")
    void retrieve_success_listsDocumentsOnRequestedDate() {
        // when
        retriever.retrieve(
                scope,
                "어제 회의 요약"
        );
        // then
        ArgumentCaptor<SearchDocumentParameters> parameters = ArgumentCaptor.forClass(SearchDocumentParameters.class);
        verify(query).listPage(
                eq(scope),
                parameters.capture(),
                isNull()
        );
        assertThat(
                parameters.getValue()
                        .recordedFrom()
        ).isEqualTo(Instant.parse("2026-10-16T15:00:00Z"));
        assertThat(
                parameters.getValue()
                        .recordedBefore()
        ).isEqualTo(Instant.parse("2026-10-17T15:00:00Z"));
        assertThat(
                parameters.getValue()
                        .query()
        ).isNull();
    }

    @Test
    @DisplayName("날짜와 검색어를 함께 적용하고 일치 문서가 없으면 빈 결과를 반환한다")
    void retrieve_success_returnsEmptyForDateAndKeywordWithoutMatches() {
        // when
        SearchRetrievalResult result = retriever.retrieve(
                scope,
                "10/17 데일리스크럼 요약"
        );
        // then
        ArgumentCaptor<SearchDocumentParameters> parameters = ArgumentCaptor.forClass(SearchDocumentParameters.class);
        verify(query).searchPage(
                eq(scope),
                parameters.capture(),
                isNull()
        );
        assertThat(
                parameters.getValue()
                        .query()
        ).isEqualTo("데일리스크럼");
        assertThat(
                parameters.getValue()
                        .recordedFrom()
        ).isEqualTo(Instant.parse("2026-10-16T15:00:00Z"));
        assertThat(result.documents()).isEmpty();
    }

    private SearchDocumentRecord document(
            long id,
            String title
    ) {
        return new SearchDocumentRecord(
                id,
                title,
                "운영",
                "문서 본문",
                DocumentStatus.ARCHIVED,
                Instant.parse("2026-10-17T00:00:00Z"),
                Instant.parse("2026-10-17T00:01:00Z"),
                Instant.parse("2026-10-17T00:02:00Z"),
                8
        );
    }
}
