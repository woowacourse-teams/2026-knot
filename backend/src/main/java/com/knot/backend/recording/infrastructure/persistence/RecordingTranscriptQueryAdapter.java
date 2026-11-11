package com.knot.backend.recording.infrastructure.persistence;

import com.knot.backend.recording.application.RecordingTranscriptQuery;
import com.knot.backend.recording.application.dto.result.RecordingTranscriptResult;
import com.knot.backend.recording.application.dto.result.TranscriptSegmentResult;
import com.knot.backend.recording.domain.repository.TranscriptSegmentRepository;
import java.util.List;
import java.util.Optional;
import java.util.Map;
import com.knot.backend.recording.application.RecordingSpeakerNames;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class RecordingTranscriptQueryAdapter implements RecordingTranscriptQuery {
    // 성공 전사는 주제 분류나 문서 생성 결과를 기다리지 않고 공개한다.
    private static final String SOURCE_SQL = """
            SELECT t.id, t.content
            FROM document_generation_batches b
            JOIN transcripts t ON t.id = b.transcript_id AND t.recording_session_id = b.recording_session_id
            WHERE b.recording_session_id = :recordingId
            """;

    private final JdbcClient jdbcClient;
    private final TranscriptSegmentRepository transcriptSegmentRepository;

    private final RecordingSpeakerNames speakerNames;

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
                                transcriptText(source.transcriptText()),
                                segments(source.transcriptId())
                        )
                );
    }

    private List<TranscriptSegmentResult> segments(long transcriptId) {
        Map<Integer, String> names = speakerNames.findByTranscriptId(transcriptId);
        return transcriptSegmentRepository.findAllByTranscriptId(transcriptId)
                .stream()
                .map(TranscriptSegmentResult::from)
                .map(segment -> segment.withDisplayName(names.get(segment.speakerNumber())))
                .toList();
    }

    private String transcriptText(String content) {
        String text = new com.knot.backend.document.infrastructure.persistence.DocumentTranscriptTextReader()
                .read(content);
        if (text.codePoints()
                .noneMatch(c -> !Character.isWhitespace(c) && !Character.isSpaceChar(c))) {
            return "";
        }
        return content;
    }

    private record TranscriptSource(
            long transcriptId,
            String transcriptText
    ) {

    }
}
