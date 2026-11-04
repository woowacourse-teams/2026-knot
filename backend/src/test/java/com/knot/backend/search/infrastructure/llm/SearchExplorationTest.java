package com.knot.backend.search.infrastructure.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.knot.backend.search.domain.SearchToolException;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

class SearchExplorationTest {

    private final JsonMapper mapper = JsonMapper.builder()
            .build();
    private final SearchExploration exploration = new SearchExploration();

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    @DisplayName("전체 목록에 문서가 있어도 질문 검색 결과가 없으면 빈 근거로 완료한다")
    void finish_success_noSearchMatchesWithListedDocuments(boolean listFirst) {
        // given
        if (listFirst) {
            exploration.record(
                    "list_documents",
                    mapper.readTree("{\"items\":[{\"documentId\":1}]}")
            );
        }
        exploration.record(
                "search_documents",
                mapper.readTree("{\"items\":[]}")
        );
        if (!listFirst) {
            exploration.record(
                    "list_documents",
                    mapper.readTree("{\"items\":[{\"documentId\":1}]}")
            );
        }

        // when & then
        assertThat(exploration.finish()).isEmpty();
    }

    @Test
    @DisplayName("검색 후보를 읽지 않으면 이후 검색이 비어도 실패한다")
    void finish_failure_unreadSearchMatches() {
        // given
        exploration.record(
                "search_documents",
                mapper.readTree("{\"items\":[{\"documentId\":1}]}")
        );
        exploration.record(
                "search_documents",
                mapper.readTree("{\"items\":[]}")
        );

        // when & then
        assertThatThrownBy(exploration::finish).isInstanceOf(SearchToolException.class)
                .hasMessage("INCOMPLETE_DOCUMENT_EXPLORATION");
    }

    @Test
    @DisplayName("문서가 있는 목록만 조회하고 본문을 읽지 않으면 실패한다")
    void finish_failure_unreadListedDocumentsWithoutSearch() {
        // given
        exploration.record(
                "list_documents",
                mapper.readTree("{\"items\":[{\"documentId\":1}]}")
        );

        // when & then
        assertThatThrownBy(exploration::finish).isInstanceOf(SearchToolException.class)
                .hasMessage("INCOMPLETE_DOCUMENT_EXPLORATION");
    }

    @Test
    @DisplayName("도구에서 읽은 문서의 녹음 시각과 생성 시각을 보존한다")
    void record_success_preservesDocumentDates() {
        // given
        String result = """
                {"document":{"documentId":1,"title":"회의","topic":"주제",
                "contentRevision":"revision","recordingStartedAt":"2026-10-27T14:50:00Z",
                "createdAt":"2026-10-27T15:10:00Z"},"content":"내용","offset":0,"truncated":false}
                """;
        // when
        exploration.record(
                "read_document",
                mapper.readTree(result)
        );
        // then
        assertThat(
                exploration.finish()
                        .getFirst()
                        .recordingStartedAt()
        ).isEqualTo(Instant.parse("2026-10-27T14:50:00Z"));
        assertThat(
                exploration.finish()
                        .getFirst()
                        .createdAt()
        ).isEqualTo(Instant.parse("2026-10-27T15:10:00Z"));
    }

    @Test
    @DisplayName("아무 도구도 호출하지 않으면 결과 없음으로 완료하지 않는다")
    void finish_failure_noQueries() {
        // when & then
        assertThatThrownBy(exploration::finish).isInstanceOf(SearchToolException.class)
                .hasMessage("INCOMPLETE_DOCUMENT_EXPLORATION");
    }

    @Test
    @DisplayName("빈 목록만 조회한 경우 기존 빈 근거 완료를 유지한다")
    void finish_success_emptyList() {
        // given
        exploration.record(
                "list_documents",
                mapper.readTree("{\"items\":[]}")
        );

        // when & then
        assertThat(exploration.finish()).isEmpty();
    }
}
