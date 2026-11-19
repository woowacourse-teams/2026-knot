package com.knot.backend.search.application.dto.result;

import com.knot.backend.search.domain.model.notion.SearchCreatedNotionPage;
import com.knot.backend.search.domain.model.notion.SearchWriteCard;
import com.knot.backend.search.domain.model.notion.SearchWriteCardDatabaseField;
import com.knot.backend.search.domain.model.notion.SearchWriteCardFailureReason;
import com.knot.backend.search.domain.model.notion.SearchWriteCardLocationKind;
import com.knot.backend.search.domain.model.notion.SearchWriteCardRetryReason;
import com.knot.backend.search.domain.model.notion.SearchWriteCardStatus;
import com.knot.backend.search.domain.model.notion.SearchWriteCardType;
import java.time.Instant;
import java.util.List;

/**
 * 쓰기 확인 카드의 지금 상태. 노션 페이지 ID와 카드에 기록한 노션 워크스페이스 같은 내부 값은 싣지 않는다(SEARCH-R48).
 * databaseFields는 일반 페이지 아래면 null이다.
 */
public record SearchWriteCardResult(
        long id,
        SearchWriteCardType type,
        SearchWriteCardStatus status,
        SearchWriteCardLocationKind locationKind,
        String locationName,
        String locationPath,
        String title,
        String body,
        List<SearchWriteCardDatabaseField> databaseFields,
        long sourceDocumentId,
        String sourceDocumentTitle,
        boolean executable,
        String createdPageUrl,
        SearchWriteCardRetryReason retryReason,
        SearchWriteCardFailureReason failureReason,
        List<ExistingPage> existingPages
) {

    /**
     * history는 같은 대화와 같은 회의 문서의 만든 페이지 이력을 최근 것부터 담는다. 카드 자신이 만든 페이지는
     * 뺀다(SEARCH-R51).
     */
    public static SearchWriteCardResult from(
            SearchWriteCard card,
            List<SearchCreatedNotionPage> history
    ) {
        return new SearchWriteCardResult(
                card.getId(),
                card.getType(),
                card.getStatus(),
                card.getLocationKind(),
                card.getLocationName(),
                card.getLocationPath(),
                card.getTitle(),
                card.getBody(),
                card.getDatabaseFields(),
                card.getSourceDocumentId(),
                card.getSourceDocumentTitle(),
                card.isExecutable(),
                card.getCreatedPageUrl(),
                card.getRetryReason(),
                card.getFailureReason(),
                history.stream()
                        .filter(page -> page.getWriteCardId() != card.getId())
                        .map(
                                page -> new ExistingPage(
                                        page.getUrl(),
                                        page.getCreatedAt()
                                )
                        )
                        .toList()
        );
    }

    public record ExistingPage(
            String url,
            Instant createdAt
    ) {
    }
}
