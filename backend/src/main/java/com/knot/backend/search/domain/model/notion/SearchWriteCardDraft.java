package com.knot.backend.search.domain.model.notion;

import com.knot.backend.search.domain.SearchErrorCode;
import com.knot.backend.search.domain.SearchException;
import java.time.LocalDate;
import java.util.List;

/**
 * 노션 페이지 만들기 초안. locationNotionId는 페이지 ID 또는 DB의 데이터 소스 ID이고 응답에 싣지 않는다.
 * databaseFields는 일반 페이지 아래면 null이다.
 */
public record SearchWriteCardDraft(
        SearchWriteCardLocationKind locationKind,
        String locationNotionId,
        String locationName,
        String locationPath,
        String title,
        String body,
        List<SearchWriteCardDatabaseField> databaseFields,
        long sourceDocumentId,
        String sourceDocumentTitle,
        LocalDate meetingDate,
        boolean executable
) {

    public SearchWriteCardDraft {
        if (locationKind == null || isBlank(locationNotionId) || locationName == null || locationPath == null) {
            throw new SearchException(SearchErrorCode.INVALID_SEARCH_STATE);
        }
        if (isBlank(title) || body == null || sourceDocumentId <= 0 || sourceDocumentTitle == null) {
            throw new SearchException(SearchErrorCode.INVALID_SEARCH_STATE);
        }
        if (locationKind == SearchWriteCardLocationKind.PAGE && databaseFields != null) {
            throw new SearchException(SearchErrorCode.INVALID_SEARCH_STATE);
        }
        if (locationKind == SearchWriteCardLocationKind.DATABASE && databaseFields == null) {
            throw new SearchException(SearchErrorCode.INVALID_SEARCH_STATE);
        }
        if (databaseFields != null) {
            databaseFields = List.copyOf(databaseFields);
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
