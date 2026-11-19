package com.knot.backend.search.application.notion;

import com.knot.backend.search.application.dto.result.SearchWriteCardResult;
import java.time.LocalDate;

/**
 * 실행 요청을 받은 카드를 잠가서 본 결과. Ready만 노션을 부른다.
 */
public sealed interface SearchWriteCardStart {

    /**
     * 요청이 카드를 대기에서 실행 중으로 바꿨다. 트랜잭션 밖에서 노션에 만든다.
     */
    record Ready(
            long workspaceId,
            SearchNotionPageCreation creation,
            LocalDate meetingDate
    ) implements SearchWriteCardStart {
    }

    /**
     * 다른 요청이 실행 중이다. 노션을 부르지 않고 끝날 때까지 기다린다.
     */
    record Running() implements SearchWriteCardStart {
    }

    /**
     * 노션을 부르지 않고 지금 상태를 준다.
     */
    record Settled(SearchWriteCardResult card) implements SearchWriteCardStart {
    }
}
