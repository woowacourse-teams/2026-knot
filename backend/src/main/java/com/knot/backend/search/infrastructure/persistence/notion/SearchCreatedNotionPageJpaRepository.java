package com.knot.backend.search.infrastructure.persistence.notion;

import com.knot.backend.search.domain.model.notion.SearchCreatedNotionPage;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

interface SearchCreatedNotionPageJpaRepository extends JpaRepository<SearchCreatedNotionPage, Long> {

    List<SearchCreatedNotionPage> findByConversationIdAndSourceDocumentIdOrderByCreatedAtDescIdDesc(
            long conversationId,
            long sourceDocumentId
    );
}
