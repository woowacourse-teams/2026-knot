package com.knot.backend.search.domain.repository.notion;

import com.knot.backend.search.domain.model.notion.SearchNotionEvidence;
import com.knot.backend.search.domain.model.notion.SearchNotionNotice;
import java.util.Collection;
import java.util.List;

public interface SearchNotionAnswerRepository {

    SearchNotionEvidence save(SearchNotionEvidence evidence);

    SearchNotionNotice save(SearchNotionNotice notice);

    /**
     * 메시지 ID와 rank 오름차순으로 돌려준다.
     */
    List<SearchNotionEvidence> findEvidences(Collection<Long> messageIds);

    List<SearchNotionNotice> findNotices(Collection<Long> messageIds);

    void deleteByMessageId(long messageId);
}
