package com.knot.backend.recording.infrastructure.persistence;

import com.knot.backend.document.infrastructure.persistence.DocumentTranscriptTextReader;
import com.knot.backend.recording.application.RecordingOutputQuery;
import com.knot.backend.recording.application.dto.result.RecordingOutputStatus;
import com.knot.backend.recording.application.dto.result.RecordingTranscriptStatus;
import com.knot.backend.recording.application.dto.result.TopicClassificationStatus;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class RecordingOutputQueryAdapter implements RecordingOutputQuery {
    private final JdbcClient jdbc;
    private final DocumentTranscriptTextReader textReader = new DocumentTranscriptTextReader();

    @Override
    public Map<Long, RecordingOutputStatus> findAll(
            long workspaceId,
            List<Long> recordingIds
    ) {
        if (recordingIds.isEmpty()) {
            return Map.of();
        }
        return jdbc.sql("""
                SELECT s.id,t.content,r.status AS transcription_status,b.id AS batch_id,
                    b.topic_registration_state,j.status AS classification_status,
                    (s.ended_at>=a.activated_at) AS eligible
                FROM recording_sessions s
                LEFT JOIN recording_transcriptions r ON r.recording_session_id=s.id
                LEFT JOIN document_generation_batches b ON b.recording_session_id=s.id
                LEFT JOIN transcripts t ON t.id=b.transcript_id AND t.recording_session_id=s.id
                LEFT JOIN document_generation_jobs j ON j.batch_id=b.id AND j.stage='CLASSIFICATION'
                LEFT JOIN recording_meeting_summary_activation a ON a.id=1
                WHERE s.workspace_id=:workspaceId AND s.id IN (:ids)
                """)
                .param(
                        "workspaceId",
                        workspaceId
                )
                .param(
                        "ids",
                        recordingIds
                )
                .query(
                        (
                                row,
                                index
                        ) -> Map.entry(
                                row.getLong("id"),
                                status(row)
                        )
                )
                .list()
                .stream()
                .collect(
                        Collectors.toMap(
                                Map.Entry::getKey,
                                Map.Entry::getValue
                        )
                );
    }

    private RecordingOutputStatus status(ResultSet row) throws SQLException {
        RecordingTranscriptStatus transcript = transcriptStatus(row);
        return new RecordingOutputStatus(
                transcript,
                topicStatus(
                        row,
                        transcript
                ),
                transcript == RecordingTranscriptStatus.READY && row.getBoolean("eligible")
        );
    }

    private RecordingTranscriptStatus transcriptStatus(ResultSet row) throws SQLException {
        String content = row.getString("content");
        if (content != null && hasText(textReader.read(content))) {
            return RecordingTranscriptStatus.READY;
        }
        if (content != null) {
            return RecordingTranscriptStatus.EMPTY;
        }
        if ("SUCCEEDED".equals(row.getString("transcription_status")) || row.getObject("batch_id") != null) {
            return RecordingTranscriptStatus.UNAVAILABLE;
        }
        if ("FAILED".equals(row.getString("transcription_status"))) {
            return RecordingTranscriptStatus.FAILED;
        }
        return RecordingTranscriptStatus.NOT_READY;
    }

    private TopicClassificationStatus topicStatus(
            ResultSet row,
            RecordingTranscriptStatus transcript
    ) throws SQLException {
        if (transcript == RecordingTranscriptStatus.EMPTY) {
            return TopicClassificationStatus.NOT_STARTED;
        }
        String state = row.getString("topic_registration_state");
        if ("TOPICS_REGISTERED".equals(state)) {
            return TopicClassificationStatus.SUCCEEDED;
        }
        if ("NO_CONTENT".equals(state)) {
            return TopicClassificationStatus.NO_CONTENT;
        }
        if ("FAILED".equals(row.getString("classification_status"))) {
            return TopicClassificationStatus.FAILED;
        }
        if ("WAITING_CLASSIFICATION".equals(state)) {
            return TopicClassificationStatus.PROCESSING;
        }
        return TopicClassificationStatus.NOT_STARTED;
    }

    static boolean hasText(String text) {
        return text != null && text.codePoints()
                .anyMatch(c -> !Character.isWhitespace(c) && !Character.isSpaceChar(c));
    }
}
