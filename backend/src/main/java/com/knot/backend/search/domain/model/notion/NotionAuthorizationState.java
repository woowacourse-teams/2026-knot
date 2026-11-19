package com.knot.backend.search.domain.model.notion;

import com.knot.backend.search.domain.SearchErrorCode;
import com.knot.backend.search.domain.SearchException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Duration;
import java.time.Instant;
import lombok.Getter;

/**
 * 노션 동의 화면으로 보낸 연결 시작 하나. state 원문은 저장하지 않고 해시만 둔다. 콜백에서 한 번 쓰면 지운다.
 */
@Getter
@Entity
@Table(name = "search_notion_authorization_states")
public class NotionAuthorizationState {

    @Id
    @Column(name = "state_hash", nullable = false, length = 64)
    private String stateHash;

    @Column(name = "workspace_id", nullable = false, updatable = false)
    private long workspaceId;

    @Column(name = "member_id", nullable = false, updatable = false)
    private long memberId;

    @Column(name = "encrypted_code_verifier", nullable = false, updatable = false)
    private String encryptedCodeVerifier;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected NotionAuthorizationState() {}

    public static NotionAuthorizationState issue(
            String stateHash,
            long workspaceId,
            long memberId,
            String encryptedCodeVerifier,
            Instant now,
            Duration ttl
    ) {
        if (stateHash == null || stateHash.isBlank() || workspaceId <= 0 || memberId <= 0
                || encryptedCodeVerifier == null || now == null || ttl == null || !ttl.isPositive()) {
            throw new SearchException(SearchErrorCode.INVALID_SEARCH_STATE);
        }
        NotionAuthorizationState state = new NotionAuthorizationState();
        state.stateHash = stateHash;
        state.workspaceId = workspaceId;
        state.memberId = memberId;
        state.encryptedCodeVerifier = encryptedCodeVerifier;
        state.createdAt = now;
        state.expiresAt = now.plus(ttl);
        return state;
    }

    public boolean isExpired(Instant now) {
        return !now.isBefore(expiresAt);
    }
}
