package com.knot.backend.search.infrastructure.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
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
    @DisplayName("두 비교 항목의 DB 연결이 모두 실패하면 공유 재시도에 원인을 전달한다")
    void retrieve_failure_preservesTemporaryDatabaseFailure() {
        when(
                query.searchPage(
                        eq(scope),
                        any(SearchDocumentParameters.class),
                        isNull()
                )
        ).thenThrow(new org.springframework.jdbc.CannotGetJdbcConnectionException("fixture"));

        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> retriever.retrieve(
                        scope,
                        "1. 10월 12일에 열린 배포 회의 조건은?\n2. 10월 13일에 열린 홍보 회의 조건은?"
                )
        )
                .isInstanceOfSatisfying(
                        com.knot.backend.search.domain.SearchToolException.class,
                        failure -> {
                            assertThat(com.knot.backend.search.domain.SearchAttemptFailure.temporary(failure)).isTrue();
                            assertThat(com.knot.backend.search.domain.SearchAttemptFailure.isNetwork(failure))
                                    .isFalse();
                        }
                );
    }

    @Test
    @DisplayName("명시한 문서 ID의 범위를 Fast Path 추가 후보로 넓히지 않는다")
    void retrieve_success_keepsExplicitDocumentReferenceOutOfAdditionalCheck() {
        List<SearchDocumentRecord> records = new java.util.ArrayList<>(
                java.util.stream.LongStream.rangeClosed(
                        1,
                        20
                )
                        .mapToObj(
                                id -> document(
                                        id,
                                        "홍보 회의"
                                )
                        )
                        .toList()
        );
        records.add(
                new SearchDocumentRecord(
                        21,
                        "홍보 회의",
                        "홍보",
                        "검수 승인 전에는 외부 공개 보류.",
                        DocumentStatus.DRAFT,
                        scope.requestAcceptedAt(),
                        scope.requestAcceptedAt(),
                        null,
                        1
                )
        );
        when(
                query.searchPage(
                        eq(scope),
                        any(SearchDocumentParameters.class),
                        isNull()
                )
        ).thenReturn(records);

        SearchRetrievalResult result = retriever.retrieve(
                scope,
                "10월 12일에 열린 문서 #42의 외부 공개 보류 조건은?"
        );

        assertThat(result.documents()).hasSize(20)
                .noneMatch(document -> document.documentId() == 21L);
        assertThat(result.additionalChecked()).isFalse();
    }

    @Test
    @DisplayName("전체 결정을 요청하면 후보 상한만 표시하고 추가 후보를 사용하지 않는다")
    void retrieve_success_keepsCandidateCapForBroadRequest() {
        List<SearchDocumentRecord> records = java.util.stream.LongStream.rangeClosed(
                1,
                21
        )
                .mapToObj(
                        id -> document(
                                id,
                                "배포 회의"
                        )
                )
                .toList();
        when(
                query.searchPage(
                        eq(scope),
                        any(SearchDocumentParameters.class),
                        isNull()
                )
        ).thenReturn(records);

        SearchRetrievalResult result = retriever.retrieve(
                scope,
                "최근 배포 회의 결정 전부 알려줘"
        );

        assertThat(result.documents()).hasSize(20)
                .noneMatch(document -> document.documentId() == 21L);
        assertThat(result.candidateLimited()).isTrue();
        assertThat(result.additionalChecked()).isFalse();
        assertThat(result.excludedCount()).isZero();
    }

    @Test
    @DisplayName("비교의 한쪽 조회가 실패해도 읽을 수 있는 항목을 보존한다")
    void retrieve_success_preservesDatedItemWhenOtherItemFails() {
        when(
                query.searchPage(
                        eq(scope),
                        any(SearchDocumentParameters.class),
                        isNull()
                )
        ).thenAnswer(call -> {
            SearchDocumentParameters parameters = call.getArgument(1);
            if (parameters.query()
                    .contains("배포")) {
                return List.of(
                        document(
                                41,
                                "배포 회의"
                        )
                );
            }
            throw new IllegalStateException("조회 실패");
        });

        SearchRetrievalResult result = retriever.retrieve(
                scope,
                "1. 10월 12일 배포 회의의 조건은?\n2. 10월 13일 홍보 회의의 조건은?"
        );

        assertThat(result.documents()).extracting(value -> value.documentId())
                .containsExactly(41L);
        assertThat(result.items()).extracting(SearchRetrievalResult.Item::status)
                .containsExactly(
                        SearchRetrievalResult.ItemStatus.MATCHED,
                        SearchRetrievalResult.ItemStatus.UNAVAILABLE
                );
    }

    @Test
    @DisplayName("SQL 후보 상한 뒤 한 건은 해당 항목의 조건을 보충할 때만 사용한다")
    void retrieve_success_recoversNextDatedSqlCandidate() {
        when(
                query.searchPage(
                        eq(scope),
                        any(SearchDocumentParameters.class),
                        isNull()
                )
        ).thenAnswer(call -> {
            SearchDocumentParameters parameters = call.getArgument(1);
            if (parameters.query()
                    .contains("배포")) {
                return List.of(
                        document(
                                41,
                                "배포 회의"
                        )
                );
            }
            return List.of(
                    document(
                            51,
                            "홍보 개요"
                    ),
                    document(
                            52,
                            "홍보 개요"
                    ),
                    document(
                            53,
                            "홍보 개요"
                    ),
                    new SearchDocumentRecord(
                            54,
                            "홍보 회의",
                            "홍보",
                            "검수 승인 전에는 외부 공개 보류.",
                            DocumentStatus.DRAFT,
                            scope.requestAcceptedAt(),
                            scope.requestAcceptedAt(),
                            null,
                            1
                    )
            );
        });

        SearchRetrievalResult result = retriever.retrieve(
                scope,
                "1. 10월 12일 배포 일정은?\n2. 10월 13일 홍보 회의에서 외부 공개를 보류한 조건은?"
        );

        assertThat(result.documents()).extracting(value -> value.documentId())
                .startsWith(41L)
                .contains(54L)
                .hasSize(4);
        assertThat(
                result.items()
                        .getLast()
                        .documentIds()
        ).contains(54L)
                .hasSize(3);
    }

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
        assertThat(result.status()).isEqualTo(SearchRetrievalResult.Status.SKIPPED);
        assertThat(result.foundNothing()).isFalse();
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

    @Test
    @DisplayName("항목별 검색에서 회의가 열린 날짜만 시간 범위로 전달한다")
    void retrieve_success_limitsOnlyMeetingDateItem() {
        String question = "1. 10월 16일에 열린 회의의 결정\n2. 10월 17일 배포 예정일을 논의한 회의";

        retriever.retrieve(
                scope,
                question
        );

        ArgumentCaptor<SearchDocumentParameters> parameters = ArgumentCaptor.forClass(SearchDocumentParameters.class);
        verify(
                query,
                times(2)
        ).searchPage(
                eq(scope),
                parameters.capture(),
                isNull()
        );
        SearchDocumentParameters meeting = parameters.getAllValues()
                .getFirst();
        SearchDocumentParameters release = parameters.getAllValues()
                .getLast();
        assertThat(meeting.recordedFrom()).isEqualTo(Instant.parse("2026-10-15T15:00:00Z"));
        assertThat(meeting.recordedBefore()).isEqualTo(Instant.parse("2026-10-16T15:00:00Z"));
        assertThat(release.recordedFrom()).isNull();
        assertThat(release.recordedBefore()).isNull();
        assertThat(release.query()).contains("10월 17일 배포");
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
