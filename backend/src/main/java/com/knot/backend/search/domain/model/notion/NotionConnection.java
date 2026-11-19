package com.knot.backend.search.domain.model.notion;

import com.knot.backend.search.domain.SearchErrorCode;
import com.knot.backend.search.domain.SearchException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import lombok.Getter;

@Getter
@Entity
@Table(name = "search_notion_connections")
public class NotionConnection {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "workspace_id", nullable = false, updatable = false)
    private long workspaceId;

    @Column(name = "connected_member_id", nullable = false)
    private long connectedMemberId;

    @Column(name = "notion_workspace_id", nullable = false, length = 100)
    private String notionWorkspaceId;

    @Column(name = "notion_workspace_name")
    private String notionWorkspaceName;

    @Column(name = "notion_workspace_icon")
    private String notionWorkspaceIcon;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private NotionConnectionStatus status;

    @Column(name = "encrypted_access_token", nullable = false)
    private String encryptedAccessToken;

    @Column(name = "encrypted_refresh_token")
    private String encryptedRefreshToken;

    @Column(name = "access_token_expires_at")
    private Instant accessTokenExpiresAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected NotionConnection() {}

    public static NotionConnection connect(
            long workspaceId,
            long memberId,
            NotionConnectionGrant grant,
            Instant now
    ) {
        if (workspaceId <= 0 || memberId <= 0 || grant == null || now == null) {
            throw new SearchException(SearchErrorCode.INVALID_SEARCH_STATE);
        }
        NotionConnection connection = new NotionConnection();
        connection.workspaceId = workspaceId;
        connection.createdAt = now;
        connection.apply(
                memberId,
                grant,
                now
        );
        return connection;
    }

    /**
     * 만료된 연결을 다시 연결한다. 연결한 사람은 다시 연결한 Member로 바뀐다(SEARCH-R34).
     */
    public void reconnect(
            long memberId,
            NotionConnectionGrant grant,
            Instant now
    ) {
        if (status != NotionConnectionStatus.EXPIRED) {
            throw new SearchException(SearchErrorCode.NOTION_CONNECTION_ALREADY_CONNECTED);
        }
        if (memberId <= 0 || grant == null || now == null) {
            throw new SearchException(SearchErrorCode.INVALID_SEARCH_STATE);
        }
        apply(
                memberId,
                grant,
                now
        );
    }

    public void renewTokens(
            String encryptedAccessToken,
            String encryptedRefreshToken,
            Instant accessTokenExpiresAt,
            Instant now
    ) {
        if (encryptedAccessToken == null || encryptedAccessToken.isBlank() || now == null) {
            throw new SearchException(SearchErrorCode.INVALID_SEARCH_STATE);
        }
        this.encryptedAccessToken = encryptedAccessToken;
        this.encryptedRefreshToken = encryptedRefreshToken;
        this.accessTokenExpiresAt = accessTokenExpiresAt;
        this.updatedAt = now;
    }

    public void expire(Instant now) {
        if (now == null) {
            throw new SearchException(SearchErrorCode.INVALID_SEARCH_STATE);
        }
        if (status == NotionConnectionStatus.EXPIRED) {
            return;
        }
        status = NotionConnectionStatus.EXPIRED;
        updatedAt = now;
    }

    public boolean isConnected() {
        return status == NotionConnectionStatus.CONNECTED;
    }

    public boolean isConnectedBy(long memberId) {
        return connectedMemberId == memberId;
    }

    public boolean isSameNotionWorkspace(String otherNotionWorkspaceId) {
        return notionWorkspaceId.equals(otherNotionWorkspaceId);
    }

    private void apply(
            long memberId,
            NotionConnectionGrant grant,
            Instant now
    ) {
        connectedMemberId = memberId;
        notionWorkspaceId = grant.notionWorkspaceId();
        notionWorkspaceName = grant.notionWorkspaceName();
        notionWorkspaceIcon = grant.notionWorkspaceIcon();
        encryptedAccessToken = grant.encryptedAccessToken();
        encryptedRefreshToken = grant.encryptedRefreshToken();
        accessTokenExpiresAt = grant.accessTokenExpiresAt();
        status = NotionConnectionStatus.CONNECTED;
        updatedAt = now;
    }
}
