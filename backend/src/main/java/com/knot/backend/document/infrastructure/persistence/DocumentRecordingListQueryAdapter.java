package com.knot.backend.document.infrastructure.persistence;

import com.knot.backend.document.application.DocumentRecordingListQuery;
import com.knot.backend.document.application.dto.result.DocumentRecordingDocumentResult;
import com.knot.backend.document.application.dto.result.DocumentRecordingItemResult;
import com.knot.backend.recording.domain.model.RecentRecordingCursor;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class DocumentRecordingListQueryAdapter implements DocumentRecordingListQuery {
    // Document가 하나 이상 있는 녹음만 시작 최신순으로 읽는다.
    // AI 제목은 최근 녹음 목록과 같이 주제가 등록된 녹음의 성공한 제목만 공개한다.
    private static final String RECORDING_PAGE_SQL = """
            SELECT s.id, s.started_at, s.accumulated_recording_millis, m.nickname, g.title
            FROM recording_sessions s
            JOIN members m ON m.id = s.member_id
            LEFT JOIN document_generation_batches b
                ON b.recording_session_id = s.id AND b.topic_registration_state = 'TOPICS_REGISTERED'
            LEFT JOIN recording_title_generations g
                ON g.recording_id = s.id AND g.status = 'SUCCEEDED' AND b.id IS NOT NULL
            WHERE s.workspace_id = :workspaceId
                AND EXISTS (SELECT 1 FROM documents d WHERE d.recording_session_id = s.id)
                AND (:afterCursor = FALSE OR s.started_at < :cursorStartedAt
                    OR (s.started_at = :cursorStartedAt AND s.id < :cursorId))
            ORDER BY s.started_at DESC, s.id DESC
            LIMIT :limit
            """;
    private static final String DOCUMENTS_SQL = """
            SELECT d.id, d.recording_session_id, d.title, d.summary
            FROM documents d
            WHERE d.workspace_id = :workspaceId AND d.recording_session_id IN (:recordingIds)
            ORDER BY d.created_at, d.id
            """;

    private final JdbcClient jdbcClient;

    @Override
    public List<DocumentRecordingItemResult> findPage(
            long workspaceId,
            RecentRecordingCursor cursor,
            int limit
    ) {
        List<RecordingRow> recordings = findRecordings(
                workspaceId,
                cursor,
                limit
        );
        if (recordings.isEmpty()) {
            return List.of();
        }
        Map<Long, List<DocumentRecordingDocumentResult>> documents = findDocuments(
                workspaceId,
                recordings
        );
        return recordings.stream()
                .map(recording -> recording.toResult(documents.get(recording.id())))
                .toList();
    }

    private List<RecordingRow> findRecordings(
            long workspaceId,
            RecentRecordingCursor cursor,
            int limit
    ) {
        return jdbcClient.sql(RECORDING_PAGE_SQL)
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
                .query(this::recording)
                .list();
    }

    private Map<Long, List<DocumentRecordingDocumentResult>> findDocuments(
            long workspaceId,
            List<RecordingRow> recordings
    ) {
        return jdbcClient.sql(DOCUMENTS_SQL)
                .param(
                        "workspaceId",
                        workspaceId
                )
                .param(
                        "recordingIds",
                        recordings.stream()
                                .map(RecordingRow::id)
                                .toList()
                )
                .query(this::document)
                .list()
                .stream()
                .collect(
                        Collectors.groupingBy(
                                DocumentRow::recordingId,
                                Collectors.mapping(
                                        DocumentRow::toResult,
                                        Collectors.toList()
                                )
                        )
                );
    }

    private RecordingRow recording(
            ResultSet resultSet,
            int rowNumber
    ) throws SQLException {
        return new RecordingRow(
                resultSet.getLong("id"),
                resultSet.getTimestamp("started_at")
                        .toInstant(),
                resultSet.getLong("accumulated_recording_millis"),
                resultSet.getString("nickname"),
                resultSet.getString("title")
        );
    }

    private DocumentRow document(
            ResultSet resultSet,
            int rowNumber
    ) throws SQLException {
        return new DocumentRow(
                resultSet.getLong("id"),
                resultSet.getLong("recording_session_id"),
                resultSet.getString("title"),
                resultSet.getString("summary")
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

    private record RecordingRow(
            long id,
            Instant startedAt,
            long recordingDurationMillis,
            String starterNickname,
            String publicAiTitle
    ) {

        private DocumentRecordingItemResult toResult(List<DocumentRecordingDocumentResult> documents) {
            return DocumentRecordingItemResult.of(
                    id,
                    startedAt,
                    recordingDurationMillis,
                    starterNickname,
                    publicAiTitle,
                    documents
            );
        }
    }

    private record DocumentRow(
            long id,
            long recordingId,
            String title,
            String summary
    ) {

        private DocumentRecordingDocumentResult toResult() {
            return new DocumentRecordingDocumentResult(
                    id,
                    title,
                    summary
            );
        }
    }
}
