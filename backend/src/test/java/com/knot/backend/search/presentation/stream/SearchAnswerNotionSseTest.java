package com.knot.backend.search.presentation.stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.knot.backend.search.application.SearchAnswerSnapshotService;
import com.knot.backend.search.application.dto.result.SearchAnswerNotionResult;
import com.knot.backend.search.application.dto.result.SearchAnswerSnapshotResult;
import com.knot.backend.search.application.dto.result.SearchNotionEvidenceItemResult;
import com.knot.backend.search.application.dto.result.SearchWriteCardResult;
import com.knot.backend.search.domain.model.SearchMessageStatus;
import com.knot.backend.search.domain.model.notion.SearchNotionNoticeReason;
import com.knot.backend.search.domain.model.notion.SearchWriteCardLocationKind;
import com.knot.backend.search.domain.model.notion.SearchWriteCardStatus;
import com.knot.backend.search.domain.model.notion.SearchWriteCardType;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

class SearchAnswerNotionSseTest {

    private final SseEmitter emitter = mock(SseEmitter.class);
    private final SearchStreamReservation reservation = mock(SearchStreamReservation.class);
    private final SearchAnswerSnapshotService snapshots = mock(SearchAnswerSnapshotService.class);
    private final List<String> events = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        when(reservation.isActive()).thenReturn(true);
        doAnswer(call -> {
            SseEmitter.SseEventBuilder event = call.getArgument(0);
            events.add(
                    event.build()
                            .stream()
                            .map(data -> String.valueOf(data.getData()))
                            .collect(Collectors.joining())
            );
            return null;
        }).when(emitter)
                .send(any(SseEmitter.SseEventBuilder.class));
    }

    @ParameterizedTest
    @EnumSource(value = SearchMessageStatus.class, names = {"COMPLETED", "FAILED", "STOPPED"})
    @DisplayName("첫 질문 스트림은 근거 다음, 끝 이벤트 바로 앞에 노션 근거, 안내 사유, 카드를 보낸다")
    void run_success_notionEventsBeforeTerminal(SearchMessageStatus status) {
        // given
        when(
                snapshots.read(
                        1,
                        2,
                        50
                )
        ).thenReturn(
                snapshot(
                        status,
                        new SearchAnswerNotionResult(
                                List.of(
                                        new SearchNotionEvidenceItemResult(
                                                "출시 계획",
                                                "https://www.notion.so/plan"
                                        )
                                ),
                                SearchNotionNoticeReason.READ_FAILED,
                                card()
                        )
                )
        );

        // when
        connection().run();

        // then
        assertThat(events).extracting(SearchAnswerNotionSseTest::name)
                .containsExactly(
                        "progress",
                        "delta",
                        "evidence",
                        "notion_evidence",
                        "notion_notice",
                        "write_card",
                        status.name()
                                .toLowerCase()
                );
        assertThat(events.get(3)).contains(
                "answerMessageId=50",
                "title=출시 계획",
                "url=https://www.notion.so/plan"
        );
        assertThat(events.get(4)).contains("reason=READ_FAILED");
        assertThat(events.get(5)).contains(
                "answerMessageId=50",
                "id=31",
                "status=PENDING"
        )
                .doesNotContain("locationNotionId");
    }

    @ParameterizedTest
    @EnumSource(value = SearchMessageStatus.class, names = {"COMPLETED", "FAILED"})
    @DisplayName("노션 값이 없으면 노션 이벤트를 보내지 않는다")
    void run_success_withoutNotionEvents(SearchMessageStatus status) {
        // given
        when(
                snapshots.read(
                        1,
                        2,
                        50
                )
        ).thenReturn(
                snapshot(
                        status,
                        SearchAnswerNotionResult.EMPTY
                )
        );

        // when
        connection().run();

        // then
        assertThat(events).extracting(SearchAnswerNotionSseTest::name)
                .containsExactly(
                        "progress",
                        "delta",
                        "evidence",
                        status.name()
                                .toLowerCase()
                );
    }

    private SearchAnswerSseConnection connection() {
        return new SearchAnswerSseConnection(
                emitter,
                reservation,
                snapshots,
                1,
                2,
                50,
                Duration.ofMillis(1)
        );
    }

    private static String name(String event) {
        return event.substring(
                "event:".length(),
                event.indexOf('\n')
        );
    }

    private SearchAnswerSnapshotResult snapshot(
            SearchMessageStatus status,
            SearchAnswerNotionResult notion
    ) {
        return new SearchAnswerSnapshotResult(
                50,
                "답변",
                status,
                null,
                null,
                null,
                null,
                List.of(),
                0,
                null,
                null,
                false,
                notion
        );
    }

    private SearchWriteCardResult card() {
        return new SearchWriteCardResult(
                31,
                SearchWriteCardType.NOTION_PAGE_CREATE,
                SearchWriteCardStatus.PENDING,
                SearchWriteCardLocationKind.PAGE,
                "회의록",
                "팀 위키 / 회의록",
                "주간 회의",
                "## 결정",
                null,
                204,
                "주간 회의",
                true,
                null,
                null,
                null,
                List.of()
        );
    }
}
