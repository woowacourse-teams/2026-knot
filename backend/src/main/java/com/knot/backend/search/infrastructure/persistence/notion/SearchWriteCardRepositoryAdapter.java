package com.knot.backend.search.infrastructure.persistence.notion;

import com.knot.backend.search.domain.model.notion.SearchCreatedNotionPage;
import com.knot.backend.search.domain.model.notion.SearchWriteCard;
import com.knot.backend.search.domain.model.notion.SearchWriteCardStatus;
import com.knot.backend.search.domain.repository.notion.SearchWriteCardRepository;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class SearchWriteCardRepositoryAdapter implements SearchWriteCardRepository {

    private final SearchWriteCardJpaRepository cards;
    private final SearchCreatedNotionPageJpaRepository createdPages;

    @Override
    public SearchWriteCard save(SearchWriteCard card) {
        return cards.save(card);
    }

    @Override
    public Optional<SearchWriteCard> findById(long cardId) {
        return cards.findById(cardId);
    }

    @Override
    public Optional<SearchWriteCard> findByIdForUpdate(long cardId) {
        return cards.findByIdForUpdate(cardId);
    }

    @Override
    public List<SearchWriteCard> findByIds(Collection<Long> cardIds) {
        if (cardIds.isEmpty()) {
            return List.of();
        }
        return cards.findAllById(cardIds);
    }

    @Override
    public List<SearchWriteCard> findByAnswerMessageIds(Collection<Long> answerMessageIds) {
        if (answerMessageIds.isEmpty()) {
            return List.of();
        }
        return cards.findByAnswerMessageIdIn(answerMessageIds);
    }

    @Override
    public List<SearchWriteCard> findPendingByConversationIdForUpdate(long conversationId) {
        return cards.findByConversationIdAndStatusForUpdate(
                conversationId,
                SearchWriteCardStatus.PENDING
        );
    }

    @Override
    public SearchCreatedNotionPage save(SearchCreatedNotionPage page) {
        return createdPages.save(page);
    }

    @Override
    public List<SearchCreatedNotionPage> findCreatedPages(
            long conversationId,
            long sourceDocumentId
    ) {
        return createdPages.findByConversationIdAndSourceDocumentIdOrderByCreatedAtDescIdDesc(
                conversationId,
                sourceDocumentId
        );
    }

    @Override
    public List<SearchCreatedNotionPage> findCreatedPages(long conversationId) {
        return createdPages.findByConversationIdOrderByCreatedAtDescIdDesc(conversationId);
    }

    @Override
    public void flush() {
        cards.flush();
    }
}
