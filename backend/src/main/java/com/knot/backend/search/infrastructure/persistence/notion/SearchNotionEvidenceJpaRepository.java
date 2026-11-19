package com.knot.backend.search.infrastructure.persistence.notion;

import com.knot.backend.search.domain.model.notion.SearchNotionEvidence;
import com.knot.backend.search.domain.model.notion.SearchNotionEvidenceId;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

interface SearchNotionEvidenceJpaRepository extends JpaRepository<SearchNotionEvidence, SearchNotionEvidenceId> {

    List<SearchNotionEvidence> findByIdMessageIdInOrderByIdMessageIdAscIdRankAsc(Collection<Long> messageIds);

    void deleteByIdMessageId(long messageId);
}
