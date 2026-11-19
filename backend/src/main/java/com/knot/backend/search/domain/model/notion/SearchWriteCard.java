package com.knot.backend.search.domain.model.notion;

import com.knot.backend.search.domain.SearchErrorCode;
import com.knot.backend.search.domain.SearchException;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import lombok.Getter;

/**
 * 쓰기 확인 카드. 상태 전이는 도메인 규칙 7. 탐색 「쓰기 확인 카드」(SEARCH-R43)를 따른다.
 */
@Getter
@Entity
@Table(name = "search_write_cards")
public class SearchWriteCard {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "answer_message_id", nullable = false, updatable = false)
    private long answerMessageId;

    @Column(name = "conversation_id", nullable = false, updatable = false)
    private long conversationId;

    @Column(name = "workspace_id", nullable = false, updatable = false)
    private long workspaceId;

    @Column(name = "member_id", nullable = false, updatable = false)
    private long memberId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40, updatable = false)
    private SearchWriteCardType type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private SearchWriteCardStatus status;

    @Column(name = "notion_workspace_id", nullable = false, length = 100, updatable = false)
    private String notionWorkspaceId;

    @Column(name = "location_notion_id", nullable = false, length = 200, updatable = false)
    private String locationNotionId;

    @Enumerated(EnumType.STRING)
    @Column(name = "location_kind", nullable = false, length = 20, updatable = false)
    private SearchWriteCardLocationKind locationKind;

    @Column(name = "location_name", nullable = false, updatable = false)
    private String locationName;

    @Column(name = "location_path", nullable = false, updatable = false)
    private String locationPath;

    @Column(nullable = false, updatable = false)
    private String title;

    @Column(nullable = false, updatable = false)
    private String body;

    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "search_write_card_database_fields", joinColumns = @JoinColumn(name = "write_card_id"))
    @OrderColumn(name = "position")
    private List<SearchWriteCardDatabaseField> databaseFields = new ArrayList<>();

    @Column(name = "source_document_id", nullable = false, updatable = false)
    private long sourceDocumentId;

    @Column(name = "source_document_title", nullable = false, updatable = false)
    private String sourceDocumentTitle;

    @Column(name = "meeting_date", updatable = false)
    private LocalDate meetingDate;

    @Column(nullable = false)
    private boolean executable;

    @Column(name = "created_page_url")
    private String createdPageUrl;

    @Enumerated(EnumType.STRING)
    @Column(name = "retry_reason", length = 40)
    private SearchWriteCardRetryReason retryReason;

    @Enumerated(EnumType.STRING)
    @Column(name = "failure_reason", length = 40)
    private SearchWriteCardFailureReason failureReason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected SearchWriteCard() {}

    public static SearchWriteCard createNotionPage(
            SearchWriteCardOrigin origin,
            String notionWorkspaceId,
            SearchWriteCardDraft draft,
            Instant now
    ) {
        if (origin == null || notionWorkspaceId == null || notionWorkspaceId.isBlank() || draft == null
                || now == null) {
            throw new SearchException(SearchErrorCode.INVALID_SEARCH_STATE);
        }
        SearchWriteCard card = new SearchWriteCard();
        card.answerMessageId = origin.answerMessageId();
        card.conversationId = origin.conversationId();
        card.workspaceId = origin.workspaceId();
        card.memberId = origin.memberId();
        card.type = SearchWriteCardType.NOTION_PAGE_CREATE;
        card.status = SearchWriteCardStatus.PENDING;
        card.notionWorkspaceId = notionWorkspaceId;
        card.applyDraft(draft);
        card.createdAt = now;
        card.updatedAt = now;
        return card;
    }

    /**
     * 대기에서 실행 중으로 바꾼다. 대기가 아니면 바꾸지 않고 false를 돌려준다(SEARCH-R43, R50).
     */
    public boolean start(Instant now) {
        if (status != SearchWriteCardStatus.PENDING) {
            return false;
        }
        if (!executable) {
            throw new SearchException(SearchErrorCode.WRITE_CARD_NOT_EXECUTABLE);
        }
        status = SearchWriteCardStatus.RUNNING;
        retryReason = null;
        updatedAt = now;
        return true;
    }

    public void complete(
            String pageUrl,
            Instant now
    ) {
        requireRunning();
        if (pageUrl == null || pageUrl.isBlank()) {
            throw new SearchException(SearchErrorCode.INVALID_SEARCH_STATE);
        }
        status = SearchWriteCardStatus.DONE;
        createdPageUrl = pageUrl;
        updatedAt = now;
    }

    public void returnToPending(
            SearchWriteCardRetryReason reason,
            Instant now
    ) {
        requireRunning();
        if (reason == null) {
            throw new SearchException(SearchErrorCode.INVALID_SEARCH_STATE);
        }
        status = SearchWriteCardStatus.PENDING;
        retryReason = reason;
        updatedAt = now;
    }

    /**
     * DB 구조가 달라졌을 때 DB 칸을 지금 구조로 갱신하고 대기로 되돌린다(SEARCH-R50).
     */
    public void replaceDatabaseFields(
            List<SearchWriteCardDatabaseField> fields,
            Instant now
    ) {
        if (locationKind != SearchWriteCardLocationKind.DATABASE || fields == null) {
            throw new SearchException(SearchErrorCode.INVALID_SEARCH_STATE);
        }
        databaseFields.clear();
        databaseFields.addAll(fields);
        returnToPending(
                SearchWriteCardRetryReason.DATABASE_FIELDS_CHANGED,
                now
        );
    }

    public void fail(
            SearchWriteCardFailureReason reason,
            Instant now
    ) {
        requireRunning();
        if (reason == null) {
            throw new SearchException(SearchErrorCode.INVALID_SEARCH_STATE);
        }
        status = SearchWriteCardStatus.FAILED;
        failureReason = reason;
        updatedAt = now;
    }

    /**
     * 대기인 카드를 취소됨으로 바꾼다. 이미 취소됨이면 그대로 둔다(SEARCH-R43).
     */
    public void cancel(Instant now) {
        if (status == SearchWriteCardStatus.CANCELED) {
            return;
        }
        if (status != SearchWriteCardStatus.PENDING) {
            throw new SearchException(SearchErrorCode.WRITE_CARD_NOT_PENDING);
        }
        status = SearchWriteCardStatus.CANCELED;
        retryReason = null;
        updatedAt = now;
    }

    public boolean isRequestedBy(long requesterId) {
        return memberId == requesterId;
    }

    public boolean isCreatedIn(String otherNotionWorkspaceId) {
        return notionWorkspaceId.equals(otherNotionWorkspaceId);
    }

    /**
     * 일반 페이지 아래에 만드는 카드는 null이다.
     */
    public List<SearchWriteCardDatabaseField> getDatabaseFields() {
        if (locationKind == SearchWriteCardLocationKind.PAGE) {
            return null;
        }
        return List.copyOf(databaseFields);
    }

    private void applyDraft(SearchWriteCardDraft draft) {
        locationKind = draft.locationKind();
        locationNotionId = draft.locationNotionId();
        locationName = draft.locationName();
        locationPath = draft.locationPath();
        title = draft.title();
        body = draft.body();
        sourceDocumentId = draft.sourceDocumentId();
        sourceDocumentTitle = draft.sourceDocumentTitle();
        meetingDate = draft.meetingDate();
        executable = draft.executable();
        if (draft.databaseFields() != null) {
            databaseFields.addAll(draft.databaseFields());
        }
    }

    private void requireRunning() {
        if (status != SearchWriteCardStatus.RUNNING) {
            throw new SearchException(SearchErrorCode.INVALID_SEARCH_STATE);
        }
    }
}
