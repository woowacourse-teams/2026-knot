package com.knot.backend.recording.infrastructure.persistence;

import com.knot.backend.recording.application.RecordingTranscriptQuery;
import com.knot.backend.recording.application.dto.result.RecordingTranscriptResult;
import com.knot.backend.recording.application.dto.result.TranscriptSegmentResult;
import com.knot.backend.recording.domain.repository.TranscriptSegmentRepository;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class RecordingTranscriptQueryAdapter implements RecordingTranscriptQuery {
    // 주제가 등록돼 NO_CONTENT가 아님이 정해진 녹음의 문서 정리 입력 전사다. 주제별 문서의 원문과 같은 Transcript다.
    private static final String SOURCE_SQL = """
            SELECT t.id, t.content
            FROM document_generation_batches b
            JOIN transcripts t ON t.id = b.transcript_id AND t.recording_session_id = b.recording_session_id
            WHERE b.recording_session_id = :recordingId AND b.topic_registration_state = 'TOPICS_REGISTERED'
            """;

    private final JdbcClient jdbcClient;
    private final TranscriptSegmentRepository transcriptSegmentRepository;

    @Override
    public Optional<RecordingTranscriptResult> find(long recordingId) {
        return jdbcClient.sql(SOURCE_SQL)
                .param(
                        "recordingId",
                        recordingId
                )
                .query(
                        (
                                resultSet,
                                rowNumber
                        ) -> new TranscriptSource(
                                resultSet.getLong("id"),
                                resultSet.getString("content")
                        )
                )
                .optional()
                .map(
                        source -> new RecordingTranscriptResult(
                                recordingId,
                                source.transcriptId(),
                                source.transcriptText(),
                                segments(source.transcriptId())
                        )
                );
    }

    private List<TranscriptSegmentResult> segments(long transcriptId) {
        return transcriptSegmentRepository.findAllByTranscriptId(transcriptId)
                .stream()
                .map(TranscriptSegmentResult::from)
                .toList();
    }

    private record TranscriptSource(
            long transcriptId,
            String transcriptText
    ) {

    }
}
