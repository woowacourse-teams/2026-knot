package com.knot.backend.search.domain.repository.notion;

import com.knot.backend.search.domain.model.notion.NotionAuthorizationState;
import java.time.Instant;
import java.util.Optional;

public interface NotionAuthorizationStateRepository {

    NotionAuthorizationState save(NotionAuthorizationState state);

    Optional<NotionAuthorizationState> findByStateHashForUpdate(String stateHash);

    void delete(NotionAuthorizationState state);

    void deleteAllExpired(Instant now);
}
