package com.knot.backend.recording.infrastructure.persistence;

import com.knot.backend.recording.application.RecentRecordingQuery;
import com.knot.backend.recording.application.dto.result.RecentRecordingItemResult;
import com.knot.backend.recording.domain.model.RecentRecordingCursor;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class RecentRecordingQueryAdapter implements RecentRecordingQuery {
    // 음성 저장이 끝난 녹음: ENDED 녹음 중 업로드 COMPLETED이거나
    // PCM 녹음의 최종화 대기(작업 전)·QUEUED·PROCESSING·READY다.
    // 최근 녹음 목록·녹음 문서 조회와 회의 전체 요약의 접수·재시도가 함께 쓴다.
    static final String OPENABLE_RECORDINGS = """
            FROM recording_sessions s
            LEFT JOIN recording_audio_uploads u ON u.recording_id = s.id
            LEFT JOIN recording_audio_finalizations f ON f.recording_id = s.id
            """;
    static final String AUDIO_SAVED = """
            s.status = 'ENDED' AND (u.status = 'COMPLETED' OR (s.audio_mode = 'PCM16_CHUNKS_V1'
                AND (f.id IS NULL OR f.status IN ('QUEUED', 'PROCESSING', 'READY'))))
            """;
    // 녹음 문서를 열 수 있는 녹음: Workspace의 음성 저장이 끝난 녹음이다.
    private static final String OPENABLE_CONDITION = "s.workspace_id = :workspaceId AND " + AUDIO_SAVED;
    // AI 제목은 주제가 등록돼 NO_CONTENT가 아님이 정해진 녹음에만 공개한다. 목록과 녹음 문서가 같은 항목을 쓴다.
    private static final String ITEM_SQL = "SELECT s.id, s.started_at, s.accumulated_recording_millis, m.nickname, g.title "
            + OPENABLE_RECORDINGS + """
                    JOIN members m ON m.id = s.member_id
                    LEFT JOIN document_generation_batches b
                        ON b.recording_session_id = s.id AND b.topic_registration_state = 'TOPICS_REGISTERED'
                    LEFT JOIN recording_title_generations g
                        ON g.recording_id = s.id AND g.status = 'SUCCEEDED' AND b.id IS NOT NULL
                    WHERE
                    """ + OPENABLE_CONDITION;
    private static final String PAGE_SQL = ITEM_SQL + """
            AND (:afterCursor = FALSE OR s.started_at < :cursorStartedAt
                OR (s.started_at = :cursorStartedAt AND s.id < :cursorId))
            ORDER BY s.started_at DESC, s.id DESC
            LIMIT :limit
            """;
    private static final String FIND_ITEM_SQL = ITEM_SQL + "AND s.id = :recordingId";
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
                .query(this::item)
                .list();
    }

    @Override
    public Optional<RecentRecordingItemResult> findItem(
            long workspaceId,
            long recordingId
    ) {
        return jdbcClient.sql(FIND_ITEM_SQL)
                .param(
                        "workspaceId",
                        workspaceId
                )
                .param(
                        "recordingId",
                        recordingId
                )
                .query(this::item)
                .optional();
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

    private RecentRecordingItemResult item(
            ResultSet resultSet,
            int rowNumber
    ) throws SQLException {
        return RecentRecordingItemResult.of(
                resultSet.getLong("id"),
                resultSet.getTimestamp("started_at")
                        .toInstant(),
                resultSet.getLong("accumulated_recording_millis"),
                resultSet.getString("nickname"),
                resultSet.getString("title")
        );
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
