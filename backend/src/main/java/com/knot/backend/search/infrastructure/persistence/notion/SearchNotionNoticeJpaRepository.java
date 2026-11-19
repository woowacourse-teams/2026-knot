package com.knot.backend.search.infrastructure.persistence.notion;

import com.knot.backend.search.domain.model.notion.SearchNotionNotice;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

interface SearchNotionNoticeJpaRepository extends JpaRepository<SearchNotionNotice, Long> {

    List<SearchNotionNotice> findByMessageIdIn(Collection<Long> messageIds);
}
