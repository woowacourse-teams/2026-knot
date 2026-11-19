package com.knot.backend.search.presentation.stream;

import com.knot.backend.search.application.dto.result.SearchAnswerNotionResult;
import com.knot.backend.search.presentation.dto.response.SearchNotionEvidenceItemResponse;
import com.knot.backend.search.presentation.dto.response.SearchWriteCardResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 끝 이벤트 바로 앞에 보낼 노션 이벤트. 값이 있는 것만 notion_evidence, notion_notice, write_card
 * 순서로 만든다.
 */
final class SearchNotionSseEvents {

    private SearchNotionSseEvents() {}

    static List<Event> of(
            String answerKey,
            long answerId,
            SearchAnswerNotionResult notion
    ) {
        List<Event> events = new ArrayList<>();
        if (!notion.evidences()
                .isEmpty()) {
            events.add(
                    new Event(
                            "notion_evidence",
                            Map.of(
                                    answerKey,
                                    answerId,
                                    "items",
                                    notion.evidences()
                                            .stream()
                                            .map(SearchNotionEvidenceItemResponse::from)
                                            .toList()
                            )
                    )
            );
        }
        if (notion.notice() != null) {
            events.add(
                    new Event(
                            "notion_notice",
                            Map.of(
                                    answerKey,
                                    answerId,
                                    "reason",
                                    notion.notice()
                                            .name()
                            )
                    )
            );
        }
        if (notion.writeCard() != null) {
            events.add(
                    new Event(
                            "write_card",
                            Map.of(
                                    answerKey,
                                    answerId,
                                    "card",
                                    SearchWriteCardResponse.from(notion.writeCard())
                            )
                    )
            );
        }
        return events;
    }

    record Event(
            String name,
            Object data
    ) {
    }
}
