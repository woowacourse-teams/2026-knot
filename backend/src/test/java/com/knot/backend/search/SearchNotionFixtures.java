package com.knot.backend.search;

import com.knot.backend.search.application.notion.SearchNotionDatabase;
import com.knot.backend.search.domain.model.notion.NotionConnection;
import com.knot.backend.search.domain.model.notion.NotionConnectionGrant;
import com.knot.backend.search.domain.model.notion.SearchNotionEvidence;
import com.knot.backend.search.domain.model.notion.SearchNotionNotice;
import com.knot.backend.search.domain.model.notion.SearchNotionNoticeReason;
import com.knot.backend.search.domain.model.notion.SearchWriteCard;
import com.knot.backend.search.domain.model.notion.SearchWriteCardDatabaseField;
import com.knot.backend.search.domain.model.notion.SearchWriteCardDatabaseFields;
import com.knot.backend.search.domain.model.notion.SearchWriteCardDraft;
import com.knot.backend.search.domain.model.notion.SearchWriteCardLocationKind;
import com.knot.backend.search.domain.model.notion.SearchWriteCardOrigin;
import com.knot.backend.search.domain.repository.notion.NotionConnectionRepository;
import com.knot.backend.search.domain.repository.notion.SearchNotionAnswerRepository;
import com.knot.backend.search.domain.repository.notion.SearchWriteCardRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 노션 연결, 쓰기 확인 카드, 노션 근거를 엔티티로 만든다. 답변 생성이 카드를 만드는 흐름은 쓰지 않는다.
 */
public class SearchNotionFixtures {
    public static final String NOTION_WORKSPACE = "notion-workspace";
    public static final String TITLE = "11월 13일 주간 회의";
    public static final String BODY = "## 결정\n- 출시 일정 확정";
    public static final LocalDate MEETING_DATE = LocalDate.parse("2026-11-13");
    public static final Instant CREATED_AT = Instant.parse("2026-11-13T01:00:00Z");
    public static final SearchNotionDatabase DATABASE = new SearchNotionDatabase(
            "data-source-id",
            "회의록",
            "팀 위키 / 회의록",
            List.of(
                    new SearchWriteCardDatabaseFields.Property(
                            "이름",
                            "title"
                    ),
                    new SearchWriteCardDatabaseFields.Property(
                            "날짜",
                            "date"
                    ),
                    new SearchWriteCardDatabaseFields.Property(
                            "참석자",
                            "people"
                    )
            )
    );

    private final JdbcClient jdbc;
    private final NotionConnectionRepository connections;
    private final SearchWriteCardRepository cards;
    private final SearchNotionAnswerRepository answers;

    public SearchNotionFixtures(
            JdbcClient jdbc,
            NotionConnectionRepository connections,
            SearchWriteCardRepository cards,
            SearchNotionAnswerRepository answers
    ) {
        this.jdbc = jdbc;
        this.connections = connections;
        this.cards = cards;
        this.answers = answers;
    }

    public void connect(
            long workspaceId,
            long memberId,
            String notionWorkspaceId
    ) {
        connections.save(
                NotionConnection.connect(
                        workspaceId,
                        memberId,
                        new NotionConnectionGrant(
                                notionWorkspaceId,
                                "Knot 팀",
                                "🪢",
                                "encrypted-access",
                                "encrypted-refresh",
                                CREATED_AT.plusSeconds(3600)
                        ),
                        CREATED_AT
                )
        );
    }

    public void expire(long workspaceId) {
        jdbc.sql("UPDATE search_notion_connections SET status = 'EXPIRED' WHERE workspace_id = :workspaceId")
                .param(
                        "workspaceId",
                        workspaceId
                )
                .update();
    }

    public SearchWriteCard pageCard(
            SearchWriteCardOrigin origin,
            long documentId
    ) {
        return save(
                origin,
                new SearchWriteCardDraft(
                        SearchWriteCardLocationKind.PAGE,
                        "page-location-id",
                        "회의록",
                        "팀 위키 / 회의록",
                        TITLE,
                        BODY,
                        null,
                        documentId,
                        "주간 회의",
                        MEETING_DATE,
                        true
                )
        );
    }

    public SearchWriteCard databaseCard(
            SearchWriteCardOrigin origin,
            long documentId,
            boolean executable
    ) {
        return save(
                origin,
                new SearchWriteCardDraft(
                        SearchWriteCardLocationKind.DATABASE,
                        DATABASE.id(),
                        DATABASE.title(),
                        DATABASE.path(),
                        TITLE,
                        BODY,
                        databaseFields(),
                        documentId,
                        "주간 회의",
                        MEETING_DATE,
                        executable
                )
        );
    }

    public static List<SearchWriteCardDatabaseField> databaseFields() {
        return SearchWriteCardDatabaseFields.from(
                DATABASE.properties(),
                TITLE,
                MEETING_DATE
        );
    }

    public void saveEvidence(
            long answerId,
            int rank,
            String title,
            String url
    ) {
        answers.save(
                SearchNotionEvidence.create(
                        answerId,
                        rank,
                        title,
                        url
                )
        );
    }

    public void saveNotice(
            long answerId,
            SearchNotionNoticeReason reason
    ) {
        answers.save(
                SearchNotionNotice.create(
                        answerId,
                        reason
                )
        );
    }

    public long createdPages() {
        return jdbc.sql("SELECT count(*) FROM search_created_notion_pages")
                .query(Long.class)
                .single();
    }

    private SearchWriteCard save(
            SearchWriteCardOrigin origin,
            SearchWriteCardDraft draft
    ) {
        return cards.save(
                SearchWriteCard.createNotionPage(
                        origin,
                        NOTION_WORKSPACE,
                        draft,
                        CREATED_AT
                )
        );
    }
}
