package com.knot.backend.search.infrastructure.persistence.notion;

import com.knot.backend.search.domain.model.notion.SearchWriteCard;
import com.knot.backend.search.domain.model.notion.SearchWriteCardStatus;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

interface SearchWriteCardJpaRepository extends JpaRepository<SearchWriteCard, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select card from SearchWriteCard card where card.id = :cardId")
    Optional<SearchWriteCard> findByIdForUpdate(long cardId);

    List<SearchWriteCard> findByAnswerMessageIdIn(Collection<Long> answerMessageIds);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select card from SearchWriteCard card
            where card.conversationId = :conversationId and card.status = :status
            order by card.id
            """)
    List<SearchWriteCard> findByConversationIdAndStatusForUpdate(
            long conversationId,
            SearchWriteCardStatus status
    );
}
