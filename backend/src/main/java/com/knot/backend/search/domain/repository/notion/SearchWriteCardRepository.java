package com.knot.backend.search.domain.repository.notion;

import com.knot.backend.search.domain.model.notion.SearchCreatedNotionPage;
import com.knot.backend.search.domain.model.notion.SearchWriteCard;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface SearchWriteCardRepository {

    SearchWriteCard save(SearchWriteCard card);

    Optional<SearchWriteCard> findById(long cardId);

    Optional<SearchWriteCard> findByIdForUpdate(long cardId);

    List<SearchWriteCard> findByIds(Collection<Long> cardIds);

    List<SearchWriteCard> findByAnswerMessageIds(Collection<Long> answerMessageIds);

    List<SearchWriteCard> findPendingByConversationIdForUpdate(long conversationId);

    SearchCreatedNotionPage save(SearchCreatedNotionPage page);

    /**
     * 같은 대화에서 같은 회의 문서로 만든 페이지 이력을 최근에 만든 것부터 돌려준다(SEARCH-R51).
     */
    List<SearchCreatedNotionPage> findCreatedPages(
            long conversationId,
            long sourceDocumentId
    );

    /**
     * 대화의 만든 페이지 이력을 최근에 만든 것부터 돌려준다. 한 페이지의 카드 이력을 한 번에 읽을 때 쓴다.
     */
    List<SearchCreatedNotionPage> findCreatedPages(long conversationId);

    void flush();
}
