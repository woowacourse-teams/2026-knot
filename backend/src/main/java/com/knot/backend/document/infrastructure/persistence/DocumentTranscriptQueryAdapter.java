package com.knot.backend.document.infrastructure.persistence;

import com.knot.backend.document.application.DocumentTranscriptQuery;
import com.knot.backend.document.application.dto.result.DocumentTranscriptSnapshot;
import com.knot.backend.recording.application.dto.result.TranscriptSegmentResult;
import com.knot.backend.recording.domain.repository.TranscriptSegmentRepository;
import java.util.Optional;
import java.util.Map;
import com.knot.backend.recording.application.RecordingSpeakerNames;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class DocumentTranscriptQueryAdapter implements DocumentTranscriptQuery {

    private final DocumentTranscriptReadJpaRepository documents;
    private final TranscriptSegmentRepository segments;

    private final RecordingSpeakerNames speakerNames;

    @Override
    public Optional<DocumentTranscriptSnapshot> find(
            long workspaceId,
            long documentId
    ) {
        return documents.findSource(
                workspaceId,
                documentId
        )
                .map(this::toSnapshot);
    }

    private DocumentTranscriptSnapshot toSnapshot(DocumentTranscriptSourceRow row) {
        Map<Integer, String> names = speakerNames.findByTranscriptId(row.transcriptId());
        return new DocumentTranscriptSnapshot(
                row.transcriptId(),
                row.recordingDurationMillis(),
                row.transcriptText(),
                segments.findAllByTranscriptId(row.transcriptId())
                        .stream()
                        .map(TranscriptSegmentResult::from)
                        .map(segment -> segment.withDisplayName(names.get(segment.speakerNumber())))
                        .toList()
        );
    }
}
