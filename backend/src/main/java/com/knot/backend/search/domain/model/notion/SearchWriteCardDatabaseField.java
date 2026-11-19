package com.knot.backend.search.domain.model.notion;

import com.knot.backend.search.domain.SearchErrorCode;
import com.knot.backend.search.domain.SearchException;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

/**
 * DB 아래에 만들 때 채워질 속성. propertyType은 노션 속성 형식(title, date)이고 응답에는 싣지 않는다.
 */
@Embeddable
public record SearchWriteCardDatabaseField(
        @Column(nullable = false) String name,
        @Column(nullable = false) String value,
        @Column(name = "property_type", nullable = false, length = 40) String propertyType
) {

    public SearchWriteCardDatabaseField {
        if (name == null || name.isBlank() || value == null || propertyType == null || propertyType.isBlank()) {
            throw new SearchException(SearchErrorCode.INVALID_SEARCH_STATE);
        }
    }
}
