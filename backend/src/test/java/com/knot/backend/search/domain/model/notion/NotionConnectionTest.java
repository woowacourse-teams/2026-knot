package com.knot.backend.search.domain.model.notion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.knot.backend.search.domain.SearchErrorCode;
import com.knot.backend.search.domain.SearchException;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class NotionConnectionTest {
    private static final Instant NOW = Instant.parse("2026-11-19T01:00:00Z");

    @Test
    @DisplayName("만료된 연결을 다시 연결하면 연결한 사람과 노션 워크스페이스가 바뀌고 연결됨으로 돌아간다")
    void reconnect_expired() {
        // given
        NotionConnection connection = NotionConnection.connect(
                1,
                10,
                grant("notion-a"),
                NOW
        );
        connection.expire(NOW);

        // when
        connection.reconnect(
                20,
                grant("notion-b"),
                NOW
        );

        // then
        assertThat(connection.isConnected()).isTrue();
        assertThat(connection.isConnectedBy(20)).isTrue();
        assertThat(connection.isSameNotionWorkspace("notion-b")).isTrue();
    }

    @Test
    @DisplayName("연결됨인 연결은 다시 연결하지 않는다")
    void reconnect_connected() {
        // given
        NotionConnection connection = NotionConnection.connect(
                1,
                10,
                grant("notion-a"),
                NOW
        );

        // when & then
        assertThatThrownBy(
                () -> connection.reconnect(
                        20,
                        grant("notion-b"),
                        NOW
                )
        ).isInstanceOf(SearchException.class)
                .extracting("errorCode")
                .isEqualTo(SearchErrorCode.NOTION_CONNECTION_ALREADY_CONNECTED);
        assertThat(connection.isConnectedBy(10)).isTrue();
    }

    private NotionConnectionGrant grant(String notionWorkspaceId) {
        return new NotionConnectionGrant(
                notionWorkspaceId,
                "Knot 팀",
                null,
                "encrypted-access",
                "encrypted-refresh",
                NOW.plusSeconds(3600)
        );
    }
}
