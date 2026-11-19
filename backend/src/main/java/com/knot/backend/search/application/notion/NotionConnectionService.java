package com.knot.backend.search.application.notion;

import com.knot.backend.global.config.SearchNotionProperties;
import com.knot.backend.member.domain.Member;
import com.knot.backend.member.domain.MemberRepository;
import com.knot.backend.search.domain.SearchErrorCode;
import com.knot.backend.search.domain.SearchException;
import com.knot.backend.search.domain.model.notion.NotionAuthorizationState;
import com.knot.backend.search.domain.model.notion.NotionConnection;
import com.knot.backend.search.domain.repository.notion.NotionAuthorizationStateRepository;
import com.knot.backend.search.domain.repository.notion.NotionConnectionRepository;
import com.knot.backend.workspace.domain.WorkspaceErrorCode;
import com.knot.backend.workspace.domain.WorkspaceException;
import com.knot.backend.workspace.domain.model.WorkspaceMemberRole;
import com.knot.backend.workspace.domain.repository.WorkspaceMemberRepository;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Workspace 노션 연결의 조회, 연결 시작, 끊기(SEARCH-R29~R31, R33, R34).
 */
@Service
@RequiredArgsConstructor
public class NotionConnectionService {

    private final WorkspaceMemberRepository memberships;
    private final MemberRepository members;
    private final NotionConnectionRepository connections;
    private final NotionAuthorizationStateRepository states;
    private final SearchNotionTokenCipher cipher;
    private final SearchNotionProperties properties;
    private final Clock clock;

    @Transactional(readOnly = true)
    public NotionConnectionResult find(
            long workspaceId,
            long memberId
    ) {
        validateMembership(
                workspaceId,
                memberId
        );
        return connections.findByWorkspaceId(workspaceId)
                .map(
                        connection -> NotionConnectionResult.of(
                                connection,
                                nickname(connection),
                                canDisconnect(
                                        connection,
                                        memberId
                                )
                        )
                )
                .orElseGet(NotionConnectionResult::notConnected);
    }

    /**
     * 연결이 없거나 만료일 때만 노션 동의 화면 주소를 만든다. state는 해시로, PKCE verifier는 암호화해 저장한다.
     */
    @Transactional
    public NotionAuthorizationStart startAuthorization(
            long workspaceId,
            long memberId
    ) {
        validateMembership(
                workspaceId,
                memberId
        );
        validateClientConfigured();
        if (connections.findByWorkspaceId(workspaceId)
                .filter(NotionConnection::isConnected)
                .isPresent()) {
            throw new SearchException(SearchErrorCode.NOTION_CONNECTION_ALREADY_CONNECTED);
        }
        Instant now = clock.instant()
                .truncatedTo(ChronoUnit.MICROS);
        states.deleteAllExpired(now);
        String state = NotionOAuthSecrets.random();
        String verifier = NotionOAuthSecrets.random();
        states.save(
                NotionAuthorizationState.issue(
                        NotionOAuthSecrets.hash(state),
                        workspaceId,
                        memberId,
                        cipher.encrypt(verifier),
                        now,
                        properties.getAuthorizationStateTtl()
                )
        );
        return new NotionAuthorizationStart(
                authorizationUrl(
                        state,
                        verifier
                ),
                state
        );
    }

    /**
     * 연결한 사람과 OWNER만 끊는다. 만료된 연결도 끊고, 대기 카드와 이력은 건드리지 않는다.
     */
    @Transactional
    public void disconnect(
            long workspaceId,
            long memberId
    ) {
        validateMembership(
                workspaceId,
                memberId
        );
        NotionConnection connection = connections.findByWorkspaceIdForUpdate(workspaceId)
                .orElseThrow(() -> new SearchException(SearchErrorCode.NOTION_CONNECTION_NOT_FOUND));
        if (!canDisconnect(
                connection,
                memberId
        )) {
            throw new SearchException(SearchErrorCode.NOTION_CONNECTION_DISCONNECT_DENIED);
        }
        connections.delete(connection);
    }

    private boolean canDisconnect(
            NotionConnection connection,
            long memberId
    ) {
        return connection.isConnectedBy(
                memberId
        ) || memberships.existsByWorkspaceIdAndMemberIdAndRole(connection.getWorkspaceId(),
                memberId,
                WorkspaceMemberRole.OWNER
        );
    }

    private String nickname(NotionConnection connection) {
        return members.findById(connection.getConnectedMemberId())
                .map(Member::getNickname)
                .orElseThrow(() -> new SearchException(SearchErrorCode.INVALID_SEARCH_STATE));
    }

    private void validateMembership(
            long workspaceId,
            long memberId
    ) {
        if (!memberships.existsByWorkspaceIdAndMemberId(
                workspaceId,
                memberId
        )) {
            throw new WorkspaceException(WorkspaceErrorCode.WORKSPACE_ACCESS_DENIED);
        }
    }

    private void validateClientConfigured() {
        if (isBlank(properties.getClientId()) || isBlank(properties.getRedirectUri())) {
            throw new SearchException(SearchErrorCode.INVALID_SEARCH_CONFIGURATION);
        }
    }

    private String authorizationUrl(
            String state,
            String verifier
    ) {
        Map<String, String> query = new LinkedHashMap<>();
        query.put(
                "response_type",
                "code"
        );
        query.put(
                "client_id",
                properties.getClientId()
        );
        query.put(
                "redirect_uri",
                properties.getRedirectUri()
        );
        query.put(
                "state",
                state
        );
        query.put(
                "code_challenge",
                NotionOAuthSecrets.challenge(verifier)
        );
        query.put(
                "code_challenge_method",
                "S256"
        );
        query.put(
                "resource",
                properties.getMcpUrl()
        );
        query.put(
                "prompt",
                "consent"
        );
        return properties.getAuthorizationEndpoint() + "?" + query.entrySet()
                .stream()
                .map(
                        entry -> entry.getKey() + "=" + URLEncoder.encode(
                                entry.getValue(),
                                StandardCharsets.UTF_8
                        )
                )
                .collect(Collectors.joining("&"));
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
