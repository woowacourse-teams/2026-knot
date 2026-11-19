package com.knot.backend.search.infrastructure.persistence.notion;

import com.knot.backend.search.domain.model.notion.SearchWriteCard;
import com.knot.backend.search.domain.model.notion.SearchWriteCardFailureReason;
import com.knot.backend.search.domain.model.notion.SearchWriteCardStatus;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

interface SearchWriteCardJpaRepository extends JpaRepository<SearchWriteCard, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select card from SearchWriteCard card where card.id = :cardId")
    Optional<SearchWriteCard> findByIdForUpdate(long cardId);

    @EntityGraph(attributePaths = "databaseFields")
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update SearchWriteCard card
            set card.status = :failed, card.failureReason = :reason, card.updatedAt = :now, card.version = card.version + 1
            where card.status = :running and card.updatedAt < :abandonedBefore
            """)
    int failAbandonedRunning(
            SearchWriteCardStatus running,
            Instant abandonedBefore,
            SearchWriteCardStatus failed,
            SearchWriteCardFailureReason reason,
            Instant now
    );

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
