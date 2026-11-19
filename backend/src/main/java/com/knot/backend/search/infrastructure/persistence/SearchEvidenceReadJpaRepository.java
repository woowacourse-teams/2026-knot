package com.knot.backend.search.infrastructure.persistence;

import com.knot.backend.search.domain.model.SearchEvidence;
import com.knot.backend.search.domain.model.SearchEvidenceId;
import java.util.List;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;

interface SearchEvidenceReadJpaRepository extends Repository<SearchEvidence, SearchEvidenceId> {

    @Query("""
            select new com.knot.backend.search.infrastructure.persistence.SearchEvidenceRow(
                e.id.messageId, d.id, d.title, d.topic, e.rank
            )
            from SearchEvidence e
            join SearchMessage m on m.id = e.id.messageId
            join SearchConversation c on c.id = m.conversationId
            join Document d on d.id = e.liveDocumentId and d.workspaceId = c.workspaceId
                and d.status in ('DRAFT', 'ARCHIVED')
            where c.workspaceId = :workspaceId and c.memberId = :memberId and c.id = :conversationId
                and m.role = 'ASSISTANT' and m.id in :answerIds and e.rank <= 3
            order by m.id, e.rank
            """)
    List<SearchEvidenceRow> findForMessages(
            long workspaceId,
            long memberId,
            long conversationId,
            List<Long> answerIds
    );
}
