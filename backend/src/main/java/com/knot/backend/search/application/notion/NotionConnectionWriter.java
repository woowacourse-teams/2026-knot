package com.knot.backend.search.application.notion;

import com.knot.backend.search.domain.model.notion.NotionAuthorizationState;
import com.knot.backend.search.domain.model.notion.NotionConnection;
import com.knot.backend.search.domain.model.notion.NotionConnectionGrant;
import com.knot.backend.search.domain.repository.notion.NotionAuthorizationStateRepository;
import com.knot.backend.search.domain.repository.notion.NotionConnectionRepository;
import com.knot.backend.workspace.domain.model.WorkspaceMember;
import com.knot.backend.workspace.domain.repository.WorkspaceMemberRepository;
import java.time.Instant;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 콜백 처리의 트랜잭션 단위. 노션 토큰 교환(HTTP)은 트랜잭션 밖에서 한다.
 */
@Component
@RequiredArgsConstructor
class NotionConnectionWriter {

    private final NotionAuthorizationStateRepository states;
    private final NotionConnectionRepository connections;
    private final WorkspaceMemberRepository memberships;

    /**
     * state를 잠그고 지운다. 같은 state로 두 번 와도 한 번만 찾는다.
     */
    @Transactional
    public Optional<NotionAuthorizationState> consume(String stateHash) {
        Optional<NotionAuthorizationState> state = states.findByStateHashForUpdate(stateHash);
        state.ifPresent(states::delete);
        return state;
    }

    /**
     * 토큰을 바꾸기 전에 거를 결과. 멤버가 아니면 FAILED, 그사이 연결됨이면 ALREADY_CONNECTED다.
     */
    @Transactional(readOnly = true)
    public Optional<NotionConnectionOutcome> rejectBeforeExchange(
            long workspaceId,
            long memberId
    ) {
        if (!memberships.existsByWorkspaceIdAndMemberId(
                workspaceId,
                memberId
        )) {
            return Optional.of(NotionConnectionOutcome.FAILED);
        }
        return connections.findByWorkspaceId(workspaceId)
                .filter(NotionConnection::isConnected)
                .map(connection -> NotionConnectionOutcome.NOTION_CONNECTION_ALREADY_CONNECTED);
    }

    /**
     * 멤버십을 잠근 뒤 연결을 만들거나 만료된 연결을 다시 연결한다. 나가기와 같은 순서(멤버십, 연결)로 잠근다. 새 연결이 동시에 생기면
     * 고유 제약 위반 예외를 호출한 쪽으로 던진다.
     */
    @Transactional
    public NotionConnectionOutcome save(
            long workspaceId,
            long memberId,
            NotionConnectionGrant grant,
            Instant now
    ) {
        boolean active = memberships.findLatestByWorkspaceIdAndMemberIdForUpdate(
                workspaceId,
                memberId
        )
                .filter(WorkspaceMember::isActive)
                .isPresent();
        if (!active) {
            return NotionConnectionOutcome.FAILED;
        }
        Optional<NotionConnection> current = connections.findByWorkspaceIdForUpdate(workspaceId);
        if (current.isEmpty()) {
            connections.save(
                    NotionConnection.connect(
                            workspaceId,
                            memberId,
                            grant,
                            now
                    )
            );
            connections.flush();
            return NotionConnectionOutcome.CONNECTED;
        }
        return reconnect(
                current.get(),
                memberId,
                grant,
                now
        );
    }

    private NotionConnectionOutcome reconnect(
            NotionConnection connection,
            long memberId,
            NotionConnectionGrant grant,
            Instant now
    ) {
        if (connection.isConnected()) {
            return NotionConnectionOutcome.NOTION_CONNECTION_ALREADY_CONNECTED;
        }
        connection.reconnect(
                memberId,
                grant,
                now
        );
        connections.save(connection);
        return NotionConnectionOutcome.CONNECTED;
    }
}
