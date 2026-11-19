package com.knot.backend.search.infrastructure.persistence.notion;

import com.knot.backend.search.domain.model.notion.SearchNotionEvidence;
import com.knot.backend.search.domain.model.notion.SearchNotionNotice;
import com.knot.backend.search.domain.repository.notion.SearchNotionAnswerRepository;
import java.util.Collection;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class SearchNotionAnswerRepositoryAdapter implements SearchNotionAnswerRepository {

    private final SearchNotionEvidenceJpaRepository evidences;
    private final SearchNotionNoticeJpaRepository notices;

    @Override
    public SearchNotionEvidence save(SearchNotionEvidence evidence) {
        return evidences.save(evidence);
    }

    @Override
    public SearchNotionNotice save(SearchNotionNotice notice) {
        return notices.save(notice);
    }

    @Override
    public List<SearchNotionEvidence> findEvidences(Collection<Long> messageIds) {
        if (messageIds.isEmpty()) {
            return List.of();
        }
        return evidences.findByIdMessageIdInOrderByIdMessageIdAscIdRankAsc(messageIds);
    }

    @Override
    public List<SearchNotionNotice> findNotices(Collection<Long> messageIds) {
        if (messageIds.isEmpty()) {
            return List.of();
        }
        return notices.findByMessageIdIn(messageIds);
    }

    @Override
    public void deleteByMessageId(long messageId) {
        evidences.deleteByIdMessageId(messageId);
        notices.deleteById(messageId);
    }
}
