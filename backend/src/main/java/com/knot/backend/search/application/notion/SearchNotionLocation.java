package com.knot.backend.search.application.notion;

import com.knot.backend.search.domain.model.notion.SearchWriteCardDatabaseField;
import com.knot.backend.search.domain.model.notion.SearchWriteCardDatabaseFields;
import com.knot.backend.search.domain.model.notion.SearchWriteCardDraft;
import com.knot.backend.search.domain.model.notion.SearchWriteCardLocationKind;
import java.time.LocalDate;
import java.util.List;

/**
 * 노션 페이지를 만들 위치. DB면 notionId에 fetchDatabase가 돌려준 id를 담고, 속성 구조를 확인하지 못했으면
 * structureKnown을 false로 둔다(SEARCH-R48, R49).
 */
public record SearchNotionLocation(
        String notionWorkspaceId,
        SearchWriteCardLocationKind kind,
        String notionId,
        String name,
        String path,
        List<SearchWriteCardDatabaseFields.Property> properties,
        boolean structureKnown
) {

    public SearchNotionLocation {
        properties = List.copyOf(properties);
    }

    static SearchNotionLocation page(
            String notionWorkspaceId,
            SearchNotionSearchHit hit
    ) {
        String name = text(
                hit.title(),
                ""
        );
        return new SearchNotionLocation(
                notionWorkspaceId,
                SearchWriteCardLocationKind.PAGE,
                hit.id(),
                name,
                text(
                        hit.path(),
                        name
                ),
                List.of(),
                true
        );
    }

    static SearchNotionLocation database(
            String notionWorkspaceId,
            SearchNotionSearchHit hit,
            SearchNotionDatabase database
    ) {
        String name = text(
                database.title(),
                text(
                        hit.title(),
                        ""
                )
        );
        return new SearchNotionLocation(
                notionWorkspaceId,
                SearchWriteCardLocationKind.DATABASE,
                database.id(),
                name,
                text(
                        database.path(),
                        text(
                                hit.path(),
                                name
                        )
                ),
                database.properties(),
                true
        );
    }

    static SearchNotionLocation unknownDatabase(
            String notionWorkspaceId,
            SearchNotionSearchHit hit
    ) {
        String name = text(
                hit.title(),
                ""
        );
        return new SearchNotionLocation(
                notionWorkspaceId,
                SearchWriteCardLocationKind.DATABASE,
                hit.id(),
                name,
                text(
                        hit.path(),
                        name
                ),
                List.of(),
                false
        );
    }

    public SearchNotionCardDraft card(
            String title,
            String body,
            long sourceDocumentId,
            String sourceDocumentTitle,
            LocalDate meetingDate
    ) {
        return new SearchNotionCardDraft(
                notionWorkspaceId,
                new SearchWriteCardDraft(
                        kind,
                        notionId,
                        name,
                        path,
                        title,
                        body,
                        databaseFields(
                                title,
                                meetingDate
                        ),
                        sourceDocumentId,
                        sourceDocumentTitle,
                        meetingDate,
                        structureKnown
                )
        );
    }

    private List<SearchWriteCardDatabaseField> databaseFields(
            String title,
            LocalDate meetingDate
    ) {
        if (kind == SearchWriteCardLocationKind.PAGE) {
            return null;
        }
        if (!structureKnown) {
            return List.of();
        }
        return SearchWriteCardDatabaseFields.from(
                properties,
                title,
                meetingDate
        );
    }

    private static String text(
            String value,
            String fallback
    ) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value;
    }
}
