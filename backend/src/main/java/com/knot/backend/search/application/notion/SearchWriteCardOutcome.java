package com.knot.backend.search.application.notion;

import com.knot.backend.search.domain.model.notion.SearchWriteCardDatabaseField;
import com.knot.backend.search.domain.model.notion.SearchWriteCardFailureReason;
import com.knot.backend.search.domain.model.notion.SearchWriteCardRetryReason;
import java.util.List;

/**
 * 노션 호출 결과로 실행 중인 카드가 바뀔 상태(SEARCH-R50, R52, R53).
 */
public sealed interface SearchWriteCardOutcome {

    static SearchWriteCardOutcome from(SearchNotionFailure failure) {
        return switch (failure) {
            case NOT_CONNECTED -> new Retry(SearchWriteCardRetryReason.NOTION_CONNECTION_REQUIRED);
            case CONNECTION_EXPIRED -> new Retry(SearchWriteCardRetryReason.NOTION_RECONNECT_REQUIRED);
            case RATE_LIMITED -> new Retry(SearchWriteCardRetryReason.NOTION_RATE_LIMITED);
            case PERMISSION_DENIED, NOT_FOUND -> new Failed(SearchWriteCardFailureReason.LOCATION_PERMISSION_LOST);
            case CONTENT_TOO_LONG -> new Failed(SearchWriteCardFailureReason.BODY_TOO_LONG);
            // 나머지 실패는 노션에 만들어졌는지 알 수 없어 무응답과 같게 확인을 안내한다.
            case TIMEOUT, FAILED -> new Failed(SearchWriteCardFailureReason.NOTION_NO_RESPONSE);
        };
    }

    record Created(
            String pageId,
            String url
    ) implements SearchWriteCardOutcome {
    }

    record Retry(SearchWriteCardRetryReason reason) implements SearchWriteCardOutcome {
    }

    record DatabaseFieldsChanged(List<SearchWriteCardDatabaseField> fields) implements SearchWriteCardOutcome {
    }

    record Failed(SearchWriteCardFailureReason reason) implements SearchWriteCardOutcome {
    }
}
