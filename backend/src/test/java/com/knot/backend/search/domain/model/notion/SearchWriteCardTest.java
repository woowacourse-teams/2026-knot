package com.knot.backend.search.domain.model.notion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.knot.backend.search.domain.SearchErrorCode;
import com.knot.backend.search.domain.SearchException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SearchWriteCardTest {
    private static final Instant NOW = Instant.parse("2026-11-19T01:00:00Z");

    @Test
    @DisplayName("대기에서 실행 중으로 가는 전이는 한 번만 성공한다")
    void start_once() {
        // given
        SearchWriteCard card = pageCard(true);

        // when
        boolean first = card.start(NOW);
        boolean second = card.start(NOW);

        // then
        assertThat(first).isTrue();
        assertThat(second).isFalse();
        assertThat(card.getStatus()).isEqualTo(SearchWriteCardStatus.RUNNING);
    }

    @Test
    @DisplayName("DB 속성을 확인하지 못한 카드는 실행을 시작하지 않는다")
    void start_notExecutable() {
        // given
        SearchWriteCard card = pageCard(false);

        // when & then
        assertThatThrownBy(() -> card.start(NOW)).isInstanceOf(SearchException.class)
                .extracting("errorCode")
                .isEqualTo(SearchErrorCode.WRITE_CARD_NOT_EXECUTABLE);
    }

    @Test
    @DisplayName("다시 누를 수 있는 오류로 대기로 돌아온 카드는 다시 실행할 때 사유를 지운다")
    void returnToPending_thenStart() {
        // given
        SearchWriteCard card = pageCard(true);
        card.start(NOW);
        card.returnToPending(
                SearchWriteCardRetryReason.NOTION_RATE_LIMITED,
                NOW
        );

        // when
        boolean started = card.start(NOW);

        // then
        assertThat(started).isTrue();
        assertThat(card.getRetryReason()).isNull();
    }

    @Test
    @DisplayName("성공하면 실행됨으로 바꾸고 만든 페이지 주소를 저장한다")
    void complete_done() {
        // given
        SearchWriteCard card = pageCard(true);
        card.start(NOW);

        // when
        card.complete(
                "https://www.notion.so/page",
                NOW
        );

        // then
        assertThat(card.getStatus()).isEqualTo(SearchWriteCardStatus.DONE);
        assertThat(card.getCreatedPageUrl()).isEqualTo("https://www.notion.so/page");
    }

    @Test
    @DisplayName("다시 실행할 수 없는 오류는 실패와 이유를 저장하고 끝 상태로 둔다")
    void fail_terminal() {
        // given
        SearchWriteCard card = pageCard(true);
        card.start(NOW);

        // when
        card.fail(
                SearchWriteCardFailureReason.NOTION_CONNECTION_CHANGED,
                NOW
        );

        // then
        assertThat(
                card.getStatus()
                        .isTerminal()
        ).isTrue();
        assertThat(card.getFailureReason()).isEqualTo(SearchWriteCardFailureReason.NOTION_CONNECTION_CHANGED);
        assertThat(card.start(NOW)).isFalse();
    }

    @Test
    @DisplayName("DB 구조가 달라지면 DB 칸을 갱신하고 대기로 되돌린다")
    void replaceDatabaseFields_pending() {
        // given
        SearchWriteCard card = databaseCard();
        card.start(NOW);
        List<SearchWriteCardDatabaseField> changed = List.of(
                new SearchWriteCardDatabaseField(
                        "제목",
                        "주간 회의",
                        "title"
                )
        );

        // when
        card.replaceDatabaseFields(
                changed,
                NOW
        );

        // then
        assertThat(card.getStatus()).isEqualTo(SearchWriteCardStatus.PENDING);
        assertThat(card.getRetryReason()).isEqualTo(SearchWriteCardRetryReason.DATABASE_FIELDS_CHANGED);
        assertThat(card.getDatabaseFields()).containsExactlyElementsOf(changed);
    }

    @Test
    @DisplayName("대기 카드만 취소하고 이미 취소한 카드의 취소는 그대로 둔다")
    void cancel_pendingOnly() {
        // given
        SearchWriteCard pending = pageCard(true);
        SearchWriteCard running = pageCard(true);
        running.start(NOW);

        // when
        pending.cancel(NOW);
        pending.cancel(NOW);

        // then
        assertThat(pending.getStatus()).isEqualTo(SearchWriteCardStatus.CANCELED);
        assertThatThrownBy(() -> running.cancel(NOW)).isInstanceOf(SearchException.class)
                .extracting("errorCode")
                .isEqualTo(SearchErrorCode.WRITE_CARD_NOT_PENDING);
    }

    @Test
    @DisplayName("일반 페이지 아래에 만드는 카드의 DB 칸은 null이다")
    void databaseFields_nullForPage() {
        // given
        SearchWriteCard card = pageCard(true);

        // when & then
        assertThat(card.getDatabaseFields()).isNull();
    }

    private SearchWriteCard pageCard(boolean executable) {
        return SearchWriteCard.createNotionPage(
                new SearchWriteCardOrigin(
                        2,
                        1,
                        1,
                        1
                ),
                "notion-workspace",
                new SearchWriteCardDraft(
                        SearchWriteCardLocationKind.PAGE,
                        "page-id",
                        "회의록",
                        "팀 위키 / 회의록",
                        "주간 회의",
                        "## 결정",
                        null,
                        10,
                        "주간 회의",
                        LocalDate.parse("2026-11-13"),
                        executable
                ),
                NOW
        );
    }

    private SearchWriteCard databaseCard() {
        return SearchWriteCard.createNotionPage(
                new SearchWriteCardOrigin(
                        2,
                        1,
                        1,
                        1
                ),
                "notion-workspace",
                new SearchWriteCardDraft(
                        SearchWriteCardLocationKind.DATABASE,
                        "data-source-id",
                        "회의록",
                        "팀 위키 / 회의록",
                        "주간 회의",
                        "## 결정",
                        List.of(
                                new SearchWriteCardDatabaseField(
                                        "이름",
                                        "주간 회의",
                                        "title"
                                )
                        ),
                        10,
                        "주간 회의",
                        LocalDate.parse("2026-11-13"),
                        true
                ),
                NOW
        );
    }
}
