package com.knot.backend.search.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.knot.backend.search.SearchFixtures;
import com.knot.backend.search.application.dto.query.SearchMessageListParameters;
import com.knot.backend.search.application.dto.result.SearchEvidenceItemResult;
import com.knot.backend.search.application.dto.result.SearchMessageListItemResult;
import com.knot.backend.search.application.dto.result.SearchMessageListResult;
import com.knot.backend.search.application.notion.SearchAnswerNotionReader;
import com.knot.backend.search.domain.model.SearchConversation;
import com.knot.backend.search.domain.SearchErrorCode;
import com.knot.backend.search.domain.SearchException;
import com.knot.backend.search.domain.model.SearchMessage;
import com.knot.backend.search.domain.model.SearchMessageRole;
import com.knot.backend.search.domain.model.SearchMessageStatus;
import com.knot.backend.workspace.domain.repository.WorkspaceMemberRepository;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SearchMessageListServiceTest {

    @Mock
    private WorkspaceMemberRepository memberships;
    @Mock
    private SearchMessageListQuery query;
    @Mock
    private SearchAnswerNotionReader notionReader;
    private SearchMessageListService service;

    @BeforeEach
    void setUp() {
        service = new SearchMessageListService(
                memberships,
                query,
                new SearchHistoryReadTransaction(),
                notionReader
        );
    }

    @Test
    @DisplayName("빈 페이지는 빈 배열과 이전 페이지 없음으로 반환한다")
    void findEmpty_success() {
        // given
        allowAccess(
                1,
                2
        );
        when(
                query.findPage(
                        1,
                        2,
                        3,
                        null,
                        30
                )
        ).thenReturn(List.of());
        // when
        SearchMessageListResult result = service.find(
                1,
                2,
                3,
                SearchMessageListParameters.of(
                        null,
                        null
                )
        );
        // then
        assertThat(result.items()).isEmpty();
        assertThat(result.hasPrevious()).isFalse();
        assertThat(result.previousCursor()).isNull();
        verify(query).findPage(
                1,
                2,
                3,
                null,
                30
        );
        verify(query).findConversation(3);
        verifyNoMoreInteractions(query);
    }

    @Test
    @DisplayName("여분 행을 제외하고 오름차순으로 반환하며 최소 순서를 커서로 만든다")
    void findPrevious_success() {
        // given
        allowAccess(
                1,
                2
        );
        SearchMessage answer = message(
                8,
                SearchMessageStatus.COMPLETED,
                "답변"
        );
        SearchMessage question = message(
                7,
                SearchMessageStatus.RECEIVED,
                "질문"
        );
        when(
                query.findPage(
                        1,
                        2,
                        3,
                        9,
                        2
                )
        ).thenReturn(
                List.of(
                        answer,
                        question,
                        mock(SearchMessage.class)
                )
        );
        SearchEvidenceItemResult evidence = new SearchEvidenceItemResult(
                10,
                "문서",
                "주제",
                1
        );
        when(
                query.findEvidences(
                        1,
                        2,
                        3,
                        List.of(8L)
                )
        ).thenReturn(
                Map.of(
                        8L,
                        List.of(evidence)
                )
        );
        // when
        SearchMessageListResult result = service.find(
                1,
                2,
                3,
                SearchMessageListParameters.of(
                        9,
                        2
                )
        );
        // then
        assertThat(result.items()).extracting(SearchMessageListItemResult::sequence)
                .containsExactly(
                        7,
                        8
                );
        assertThat(
                result.items()
                        .getFirst()
                        .evidences()
        ).isEmpty();
        assertThat(
                result.items()
                        .getLast()
                        .evidences()
        ).containsExactly(evidence);
        assertThat(result.hasPrevious()).isTrue();
        assertThat(result.previousCursor()).isEqualTo("7");
        verify(query).findEvidences(
                1,
                2,
                3,
                List.of(8L)
        );
    }

    @ParameterizedTest
    @EnumSource(value = SearchMessageStatus.class, names = {"STREAMING", "COMPLETED", "FAILED", "STOPPED"})
    @DisplayName("답변 본문과 상태를 그대로 복구하고 정확히 size개면 이전 페이지는 없다")
    void findContent_success(SearchMessageStatus status) {
        // given
        allowAccess(
                1,
                2
        );
        String content = "  질문\n답변\t😀 " + "긴 본문".repeat(200);
        SearchMessage answer = message(
                2,
                status,
                content
        );
        when(
                query.findPage(
                        1,
                        2,
                        3,
                        null,
                        1
                )
        ).thenReturn(List.of(answer));
        when(
                query.findEvidences(
                        1,
                        2,
                        3,
                        List.of(2L)
                )
        ).thenReturn(Map.of());
        // when
        SearchMessageListResult result = service.find(
                1,
                2,
                3,
                SearchMessageListParameters.of(
                        null,
                        1
                )
        );
        // then
        assertThat(
                result.items()
                        .getFirst()
                        .content()
        ).isEqualTo(content);
        assertThat(
                result.items()
                        .getFirst()
                        .status()
        ).isEqualTo(status);
        assertThat(
                result.items()
                        .getFirst()
                        .createdAt()
        ).isEqualTo(SearchFixtures.TIME);
        assertThat(result.hasPrevious()).isFalse();
        assertThat(result.previousCursor()).isNull();
    }

    @Test
    @DisplayName("빈 STREAMING 본문도 null로 바꾸지 않는다")
    void findStreamingEmpty_success() {
        // given
        allowAccess(
                1,
                2
        );
        SearchMessage answer = message(
                2,
                SearchMessageStatus.STREAMING,
                ""
        );
        when(
                query.findPage(
                        1,
                        2,
                        3,
                        null,
                        1
                )
        ).thenReturn(List.of(answer));
        when(
                query.findEvidences(
                        1,
                        2,
                        3,
                        List.of(2L)
                )
        ).thenReturn(Map.of());
        // when
        SearchMessageListResult result = service.find(
                1,
                2,
                3,
                SearchMessageListParameters.of(
                        null,
                        1
                )
        );
        // then
        assertThat(
                result.items()
                        .getFirst()
                        .content()
        ).isEmpty();
    }

    @Test
    @DisplayName("현재 멤버가 아니면 대화와 메시지를 읽지 않는다")
    void find_failure_membershipDenied() {
        // when & then
        assertError(
                SearchErrorCode.SEARCH_ACCESS_DENIED,
                1,
                2,
                3
        );
        verifyNoInteractions(query);
    }

    @Test
    @DisplayName("존재하지 않는 대화는 404 계약으로 구분한다")
    void find_failure_missingConversation() {
        // given
        when(
                memberships.existsByWorkspaceIdAndMemberId(
                        1L,
                        2L
                )
        ).thenReturn(true);
        when(query.findConversation(3)).thenReturn(Optional.empty());
        // when & then
        assertError(
                SearchErrorCode.CONVERSATION_NOT_FOUND,
                1,
                2,
                3
        );
    }

    @Test
    @DisplayName("다른 소유자의 대화는 접근을 거절한다")
    void find_failure_otherOwner() {
        // given
        allowAccess(
                1,
                99
        );
        // when & then
        assertError(
                SearchErrorCode.SEARCH_ACCESS_DENIED,
                1,
                2,
                3
        );
    }

    @Test
    @DisplayName("다른 Workspace 대화는 접근을 거절한다")
    void find_failure_otherWorkspace() {
        // given
        allowAccess(
                99,
                2
        );
        // when & then
        assertError(
                SearchErrorCode.SEARCH_ACCESS_DENIED,
                1,
                2,
                3
        );
    }

    @ParameterizedTest
    @ValueSource(longs = {-1, 0})
    @DisplayName("양수가 아닌 경로 ID는 DB 조회 전에 거절한다")
    void find_failure_invalidPath(long id) {
        // when & then
        assertError(
                SearchErrorCode.INVALID_PARAMETER,
                id,
                2,
                3
        );
        assertError(
                SearchErrorCode.INVALID_PARAMETER,
                1,
                2,
                id
        );
        verifyNoInteractions(
                memberships,
                query
        );
    }

    private void allowAccess(
            long workspaceId,
            long ownerId
    ) {
        when(
                memberships.existsByWorkspaceIdAndMemberId(
                        1L,
                        2L
                )
        ).thenReturn(true);
        when(query.findConversation(3)).thenReturn(
                Optional.of(
                        SearchConversation.create(
                                workspaceId,
                                ownerId,
                                SearchFixtures.TIME
                        )
                )
        );
    }

    private SearchMessage message(
            int sequence,
            SearchMessageStatus status,
            String content
    ) {
        SearchMessage message = mock(SearchMessage.class);
        when(message.getId()).thenReturn((long) sequence);
        when(message.getSequence()).thenReturn(sequence);
        when(message.getRole()).thenReturn(role(status));
        when(message.getContent()).thenReturn(content);
        when(message.getStatus()).thenReturn(status);
        when(message.getCreatedAt()).thenReturn(SearchFixtures.TIME);
        return message;
    }

    private SearchMessageRole role(SearchMessageStatus status) {
        if (status == SearchMessageStatus.RECEIVED) {
            return SearchMessageRole.USER;
        }
        return SearchMessageRole.ASSISTANT;
    }

    private void assertError(
            SearchErrorCode code,
            long workspaceId,
            long memberId,
            long conversationId
    ) {
        assertThatExceptionOfType(SearchException.class).isThrownBy(
                () -> service.find(
                        workspaceId,
                        memberId,
                        conversationId,
                        SearchMessageListParameters.of(
                                null,
                                null
                        )
                )
        )
                .satisfies(error -> assertThat(error.getErrorCode()).isEqualTo(code));
    }
}
