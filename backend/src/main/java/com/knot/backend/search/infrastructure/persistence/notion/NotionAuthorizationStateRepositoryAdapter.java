package com.knot.backend.search.infrastructure.persistence.notion;

import com.knot.backend.search.domain.model.notion.NotionAuthorizationState;
import com.knot.backend.search.domain.repository.notion.NotionAuthorizationStateRepository;
import java.time.Instant;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class NotionAuthorizationStateRepositoryAdapter implements NotionAuthorizationStateRepository {

    private final NotionAuthorizationStateJpaRepository states;

    @Override
    public NotionAuthorizationState save(NotionAuthorizationState state) {
        return states.save(state);
    }

    @Override
    public Optional<NotionAuthorizationState> findByStateHashForUpdate(String stateHash) {
        return states.findByStateHashForUpdate(stateHash);
    }

    @Override
    public void delete(NotionAuthorizationState state) {
        states.delete(state);
    }

    @Override
    public void deleteAllExpired(Instant now) {
        states.deleteAllExpired(now);
    }
}
