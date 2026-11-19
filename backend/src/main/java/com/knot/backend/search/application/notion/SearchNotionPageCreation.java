package com.knot.backend.search.application.notion;

import com.knot.backend.search.domain.model.notion.SearchWriteCardDatabaseField;
import com.knot.backend.search.domain.model.notion.SearchWriteCardLocationKind;
import java.util.List;

/**
 * 만들 페이지. 일반 페이지 아래면 databaseFields가 비어 있고 제목은 title로 넣는다. content는 노션 마크다운이다.
 */
public record SearchNotionPageCreation(
        SearchWriteCardLocationKind parentKind,
        String parentId,
        String title,
        List<SearchWriteCardDatabaseField> databaseFields,
        String content
) {

    public SearchNotionPageCreation {
        databaseFields = List.copyOf(databaseFields);
    }
}
