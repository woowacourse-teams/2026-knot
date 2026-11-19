package com.knot.backend.search.infrastructure.persistence.notion;

import com.knot.backend.search.domain.model.notion.NotionAuthorizationState;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

interface NotionAuthorizationStateJpaRepository extends JpaRepository<NotionAuthorizationState, String> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select state from NotionAuthorizationState state where state.stateHash = :stateHash")
    Optional<NotionAuthorizationState> findByStateHashForUpdate(String stateHash);

    @Modifying
    @Query("delete from NotionAuthorizationState state where state.expiresAt <= :now")
    void deleteAllExpired(Instant now);
}
