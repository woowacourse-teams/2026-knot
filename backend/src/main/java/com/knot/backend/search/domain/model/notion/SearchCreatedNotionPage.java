package com.knot.backend.search.domain.model.notion;

import com.knot.backend.search.domain.SearchErrorCode;
import com.knot.backend.search.domain.SearchException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;

/**
 * 만든 페이지 이력. Knot이 노션의 성공 응답을 받아 기록한 페이지만 남긴다(SEARCH-R51).
 */
@Getter
@Entity
@Table(name = "search_created_notion_pages")
public class SearchCreatedNotionPage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "write_card_id", nullable = false, updatable = false)
    private long writeCardId;

    @Column(name = "workspace_id", nullable = false, updatable = false)
    private long workspaceId;

    @Column(name = "conversation_id", nullable = false, updatable = false)
    private long conversationId;

    @Column(name = "source_document_id", nullable = false, updatable = false)
    private long sourceDocumentId;

    @Column(name = "notion_workspace_id", nullable = false, length = 100, updatable = false)
    private String notionWorkspaceId;

    @Column(name = "notion_page_id", nullable = false, length = 200, updatable = false)
    private String notionPageId;

    @Column(nullable = false, updatable = false)
    private String url;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected SearchCreatedNotionPage() {}

    public static SearchCreatedNotionPage record(
            SearchWriteCard card,
            String notionPageId,
            String url,
            Instant now
    ) {
        if (card == null || card.getId() == null || notionPageId == null || notionPageId.isBlank()) {
            throw new SearchException(SearchErrorCode.INVALID_SEARCH_STATE);
        }
        if (url == null || url.isBlank() || now == null) {
            throw new SearchException(SearchErrorCode.INVALID_SEARCH_STATE);
        }
        SearchCreatedNotionPage page = new SearchCreatedNotionPage();
        page.writeCardId = card.getId();
        page.workspaceId = card.getWorkspaceId();
        page.conversationId = card.getConversationId();
        page.sourceDocumentId = card.getSourceDocumentId();
        page.notionWorkspaceId = card.getNotionWorkspaceId();
        page.notionPageId = notionPageId;
        page.url = url;
        page.createdAt = now;
        return page;
    }
}
