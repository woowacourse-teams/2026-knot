package com.knot.backend.workspace.domain.model;
import static org.assertj.core.api.Assertions.*;
import com.knot.backend.workspace.domain.WorkspaceException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import org.junit.jupiter.api.Test;
class WorkspaceMemberCursorTest {
    @Test
    void rejectsTimestampOutsideDatabaseRange() {
        String encoded = Base64.getUrlEncoder()
                .encodeToString("1|1|+300000-01-01T00:00:00Z|1".getBytes(StandardCharsets.UTF_8));
        assertThatThrownBy(
                () -> WorkspaceMemberCursor.parse(
                        encoded,
                        1
                )
        ).isInstanceOf(WorkspaceException.class);
        var cursor = WorkspaceMemberCursor.of(
                1,
                Instant.parse("2026-11-11T00:00:00Z"),
                2
        );
        assertThat(
                WorkspaceMemberCursor.parse(
                        cursor.encode(),
                        1
                )
                        .getJoinedAt()
        ).isEqualTo(cursor.getJoinedAt());
    }
}
