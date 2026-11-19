package com.knot.backend.search.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.knot.backend.search.application.dto.result.SearchAnswerSnapshotResult;
import com.knot.backend.search.application.notion.SearchAnswerNotionReader;
import com.knot.backend.search.domain.model.SearchAnswerExecution;
import com.knot.backend.search.domain.model.SearchConversation;
import com.knot.backend.search.domain.SearchException;
import com.knot.backend.search.domain.model.SearchMessage;
import com.knot.backend.search.domain.repository.SearchRepository;
import com.knot.backend.workspace.domain.repository.WorkspaceMemberRepository;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class SearchAnswerSnapshotServiceTest {
    private static final Instant CONTENT_UPDATED_AT = Instant.parse("2026-10-16T00:00:00Z");

    private final SearchRepository repository = mock(SearchRepository.class);
    private final WorkspaceMemberRepository memberships = mock(WorkspaceMemberRepository.class);
    private final SearchMessageListQuery messages = mock(SearchMessageListQuery.class);
    private final SearchAnswerSnapshotService service = new SearchAnswerSnapshotService(
            repository,
            memberships,
            messages,
            new SearchHistoryReadTransaction(),
            mock(SearchAnswerNotionReader.class)
    );

    @BeforeEach
    void setUp() {
        SearchConversation conversation = mock(SearchConversation.class);
        when(conversation.getId()).thenReturn(40L);
        when(conversation.getWorkspaceId()).thenReturn(1L);
        when(conversation.getMemberId()).thenReturn(2L);
        when(conversation.getUpdatedAt()).thenReturn(CONTENT_UPDATED_AT);
        when(repository.findConversation(40)).thenReturn(Optional.of(conversation));
        when(
                memberships.existsByWorkspaceIdAndMemberId(
                        1L,
                        2L
                )
        ).thenReturn(true);
        SearchMessage answer = SearchMessage.startAnswer(
                40,
                2,
                Instant.EPOCH
        );
        answer.appendContent("저장된 답변 😀");
        when(repository.findMessage(50)).thenReturn(Optional.of(answer));
        SearchAnswerExecution execution = mock(SearchAnswerExecution.class);
        when(execution.getConversationId()).thenReturn(40L);
        when(repository.findExecution(50)).thenReturn(Optional.of(execution));
    }

    @Test
    @DisplayName("본인 대화의 ASSISTANT 답변 본문을 읽는다")
    void readOwnAnswer_success() {
        SearchAnswerSnapshotResult snapshot = service.read(
                1,
                2,
                40,
                50
        );
        assertThat(snapshot.answerId()).isEqualTo(50);
        assertThat(snapshot.content()).isEqualTo("저장된 답변 😀");
        assertThat(snapshot.contentUpdatedAt()).isEqualTo(CONTENT_UPDATED_AT);
    }

    @ParameterizedTest
    @CsvSource({"3,2", "1,3"})
    @DisplayName("다른 Workspace 또는 소유자는 거절한다")
    void readForeignScope_failure(
            long workspaceId,
            long memberId
    ) {
        assertCode(
                workspaceId,
                memberId,
                40,
                50,
                "SEARCH_ACCESS_DENIED"
        );
    }

    @Test
    @DisplayName("탈퇴한 멤버는 본인 대화도 읽을 수 없다")
    void readFormerMember_failure() {
        when(
                memberships.existsByWorkspaceIdAndMemberId(
                        1L,
                        2L
                )
        ).thenReturn(false);
        assertCode(
                1,
                2,
                40,
                50,
                "SEARCH_ACCESS_DENIED"
        );
    }

    @Test
    @DisplayName("존재하지 않는 대화는 답변 없음으로 처리한다")
    void readMissingConversation_failure() {
        assertCode(
                1,
                2,
                99,
                50,
                "ANSWER_NOT_FOUND"
        );
    }

    @Test
    @DisplayName("존재하지 않는 답변은 404로 처리한다")
    void readMissingAnswer_failure() {
        assertCode(
                1,
                2,
                40,
                99,
                "ANSWER_NOT_FOUND"
        );
    }

    @Test
    @DisplayName("USER 메시지는 답변으로 복구하지 않는다")
    void readQuestionAsAnswer_failure() {
        when(repository.findMessage(50)).thenReturn(
                Optional.of(
                        SearchMessage.receiveQuestion(
                                40,
                                1,
                                "질문",
                                Instant.EPOCH
                        )
                )
        );
        assertCode(
                1,
                2,
                40,
                50,
                "ANSWER_NOT_FOUND"
        );
    }

    @Test
    @DisplayName("다른 대화의 답변을 현재 경로로 읽지 않는다")
    void readMismatchedAnswer_failure() {
        when(repository.findMessage(50)).thenReturn(
                Optional.of(
                        SearchMessage.startAnswer(
                                41,
                                2,
                                Instant.EPOCH
                        )
                )
        );
        assertCode(
                1,
                2,
                40,
                50,
                "ANSWER_NOT_FOUND"
        );
    }

    @Test
    @DisplayName("답변의 실행 정보가 없으면 서버 오류를 반환한다")
    void readMissingExecution_failure() {
        when(repository.findExecution(50)).thenReturn(Optional.empty());
        assertCode(
                1,
                2,
                40,
                50,
                "ANSWER_GENERATION_UNAVAILABLE"
        );
    }

    private void assertCode(
            long workspaceId,
            long memberId,
            long conversationId,
            long answerId,
            String code
    ) {
        assertThatThrownBy(
                () -> service.read(
                        workspaceId,
                        memberId,
                        conversationId,
                        answerId
                )
        ).isInstanceOf(SearchException.class)
                .extracting("errorCode.code")
                .isEqualTo(code);
    }
}
