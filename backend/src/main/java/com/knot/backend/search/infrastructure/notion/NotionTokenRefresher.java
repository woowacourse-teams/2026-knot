package com.knot.backend.search.infrastructure.notion;

import com.knot.backend.search.application.notion.SearchNotionException;
import com.knot.backend.search.application.notion.SearchNotionFailure;
import com.knot.backend.search.application.notion.SearchNotionIssuedGrant;
import com.knot.backend.search.application.notion.SearchNotionTokenCipher;
import com.knot.backend.search.domain.model.notion.NotionConnection;
import com.knot.backend.search.domain.repository.notion.NotionConnectionRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 연결 행을 잠근 새 트랜잭션에서 토큰을 갱신한다. 노션은 refresh token을 매번 회전하고 버린 토큰을 다시 쓰면 grant를
 * 취소하므로, 연결 하나당 한 번에 한 갱신만 하고 새 access와 refresh 토큰을 함께 저장한다. 호출한 쪽 트랜잭션이 롤백돼도
 * 회전한 토큰은 남아야 해서 REQUIRES_NEW로 커밋한다. 호출한 쪽은 연결 행을 잠근 채 노션을 부르면 안 된다.
 */
@Component
@RequiredArgsConstructor
class NotionTokenRefresher {

    private final NotionConnectionRepository connections;
    private final NotionOAuthClient oauth;
    private final SearchNotionTokenCipher cipher;
    private final Clock clock;

    /**
     * usedEncryptedAccessToken은 거절당했거나 만료된 토큰이다. 그사이 다른 요청이 이미 갱신했으면 노션을 부르지 않고 저장된
     * 새 토큰을 돌려준다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, noRollbackFor = SearchNotionException.class)
    public String refresh(
            long workspaceId,
            String usedEncryptedAccessToken,
            Instant deadline
    ) {
        NotionConnection connection = connections.findByWorkspaceIdForUpdate(workspaceId)
                .orElseThrow(() -> new SearchNotionException(SearchNotionFailure.NOT_CONNECTED));
        if (!connection.isConnected()) {
            throw new SearchNotionException(SearchNotionFailure.CONNECTION_EXPIRED);
        }
        if (!connection.getEncryptedAccessToken()
                .equals(usedEncryptedAccessToken)) {
            return cipher.decrypt(connection.getEncryptedAccessToken());
        }
        if (connection.getEncryptedRefreshToken() == null) {
            throw expire(connection);
        }
        Optional<SearchNotionIssuedGrant> renewed = oauth.refresh(
                cipher.decrypt(connection.getEncryptedRefreshToken()),
                deadline
        );
        if (renewed.isEmpty()) {
            throw expire(connection);
        }
        return save(
                connection,
                renewed.get()
        );
    }

    /**
     * 갱신한 토큰까지 거절되면 연결을 만료로 바꾼다. 그사이 다른 요청이 또 갱신해 저장된 토큰이 달라졌으면 그대로 둔다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public SearchNotionException expireRejected(
            long workspaceId,
            String rejectedAccessToken
    ) {
        Optional<NotionConnection> found = connections.findByWorkspaceIdForUpdate(workspaceId);
        if (found.isEmpty()) {
            return new SearchNotionException(SearchNotionFailure.NOT_CONNECTED);
        }
        NotionConnection connection = found.get();
        if (!cipher.decrypt(connection.getEncryptedAccessToken())
                .equals(rejectedAccessToken)) {
            return new SearchNotionException(SearchNotionFailure.FAILED);
        }
        return expire(connection);
    }

    private String save(
            NotionConnection connection,
            SearchNotionIssuedGrant renewed
    ) {
        connection.renewTokens(
                cipher.encrypt(renewed.accessToken()),
                refreshToken(
                        connection,
                        renewed
                ),
                renewed.accessTokenExpiresAt(),
                now()
        );
        connections.save(connection);
        return renewed.accessToken();
    }

    private String refreshToken(
            NotionConnection connection,
            SearchNotionIssuedGrant renewed
    ) {
        if (renewed.refreshToken() == null) {
            return connection.getEncryptedRefreshToken();
        }
        return cipher.encrypt(renewed.refreshToken());
    }

    private SearchNotionException expire(NotionConnection connection) {
        connection.expire(now());
        connections.save(connection);
        return new SearchNotionException(SearchNotionFailure.CONNECTION_EXPIRED);
    }

    private Instant now() {
        return clock.instant()
                .truncatedTo(ChronoUnit.MICROS);
    }
}
