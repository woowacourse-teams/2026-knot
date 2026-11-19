package com.knot.backend.search.application.notion;

import com.knot.backend.search.domain.repository.notion.NotionConnectionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 연결한 사람이 Workspace를 나가면 탈퇴 트랜잭션 안에서 노션 연결을 지운다(SEARCH-R33). 대기 카드와 이력은 지우지
 * 않는다.
 */
@Service
@RequiredArgsConstructor
public class NotionConnectionDepartureService {

    private final NotionConnectionRepository connections;

    @Transactional
    public void disconnectAfterDeparture(
            long workspaceId,
            long memberId
    ) {
        connections.findByWorkspaceIdForUpdate(workspaceId)
                .filter(connection -> connection.isConnectedBy(memberId))
                .ifPresent(connections::delete);
    }
}
