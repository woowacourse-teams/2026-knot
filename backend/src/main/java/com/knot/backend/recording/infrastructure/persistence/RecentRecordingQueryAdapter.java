package com.knot.backend.recording.infrastructure.persistence;

import com.knot.backend.recording.application.RecentRecordingQuery;
import com.knot.backend.recording.application.dto.result.RecentRecordingItemResult;
import com.knot.backend.recording.domain.model.RecentRecordingCursor;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class RecentRecordingQueryAdapter implements RecentRecordingQuery {
    // 녹음 문서를 열 수 있는 녹음: Workspace의 ENDED 녹음 중 음성 저장이 끝난 녹음이다. 업로드 COMPLETED이거나
    // PCM 녹음의 최종화 대기(작업 전)·QUEUED·PROCESSING·READY다. 최근 녹음 목록과 녹음 문서 조회가 함께 쓴다.
    private static final String OPENABLE_RECORDINGS = """
            FROM recording_sessions s
            LEFT JOIN recording_audio_uploads u ON u.recording_id = s.id
            LEFT JOIN recording_audio_finalizations f ON f.recording_id = s.id
            """;
    private static final String OPENABLE_CONDITION = """
            s.workspace_id = :workspaceId AND s.status = 'ENDED'
                AND (u.status = 'COMPLETED' OR (s.audio_mode = 'PCM16_CHUNKS_V1'
                    AND (f.id IS NULL OR f.status IN ('QUEUED', 'PROCESSING', 'READY'))))
            """;
    // AI 제목은 주제가 등록돼 NO_CONTENT가 아님이 정해진 녹음에만 공개한다.
    private static final String PAGE_SQL = "SELECT s.id, s.started_at, s.accumulated_recording_millis, m.nickname, g.title "
            + OPENABLE_RECORDINGS + """
                    JOIN members m ON m.id = s.member_id
                    LEFT JOIN document_generation_batches b
                        ON b.recording_session_id = s.id AND b.topic_registration_state = 'TOPICS_REGISTERED'
                    LEFT JOIN recording_title_generations g
                        ON g.recording_id = s.id AND g.status = 'SUCCEEDED' AND b.id IS NOT NULL
                    WHERE
                    """ + OPENABLE_CONDITION + """
                    AND (:afterCursor = FALSE OR s.started_at < :cursorStartedAt
                        OR (s.started_at = :cursorStartedAt AND s.id < :cursorId))
                    ORDER BY s.started_at DESC, s.id DESC
                    LIMIT :limit
                    """;
    private static final String CONTAINS_SQL = "SELECT EXISTS (SELECT 1 " + OPENABLE_RECORDINGS + "WHERE "
            + OPENABLE_CONDITION + "AND s.id = :recordingId)";

    private final JdbcClient jdbcClient;

    @Override
    public List<RecentRecordingItemResult> findPage(
            long workspaceId,
            RecentRecordingCursor cursor,
            int limit
    ) {
        return jdbcClient.sql(PAGE_SQL)
                .param(
                        "workspaceId",
                        workspaceId
                )
                .param(
                        "afterCursor",
                        cursor != null
                )
                .param(
                        "cursorStartedAt",
                        Timestamp.from(cursorStartedAt(cursor))
                )
                .param(
                        "cursorId",
                        cursorId(cursor)
                )
                .param(
                        "limit",
                        limit
                )
                .query(
                        (
                                resultSet,
                                rowNumber
                        ) -> RecentRecordingItemResult.of(
                                resultSet.getLong("id"),
                                resultSet.getTimestamp("started_at")
                                        .toInstant(),
                                resultSet.getLong("accumulated_recording_millis"),
                                resultSet.getString("nickname"),
                                resultSet.getString("title")
                        )
                )
                .list();
    }

    @Override
    public boolean contains(
            long workspaceId,
            long recordingId
    ) {
        return jdbcClient.sql(CONTAINS_SQL)
                .param(
                        "workspaceId",
                        workspaceId
                )
                .param(
                        "recordingId",
                        recordingId
                )
                .query(Boolean.class)
                .single();
    }

    private Instant cursorStartedAt(RecentRecordingCursor cursor) {
        if (cursor == null) {
            return Instant.EPOCH;
        }
        return cursor.getStartedAt();
    }

    private long cursorId(RecentRecordingCursor cursor) {
        if (cursor == null) {
            return 0;
        }
        return cursor.getRecordingId();
    }
}
