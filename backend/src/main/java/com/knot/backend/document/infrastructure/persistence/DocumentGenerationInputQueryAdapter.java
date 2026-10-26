package com.knot.backend.document.infrastructure.persistence;

import com.knot.backend.document.application.DocumentGenerationInputQuery;
import com.knot.backend.document.application.dto.result.DocumentGenerationInputResult;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class DocumentGenerationInputQueryAdapter implements DocumentGenerationInputQuery {
    private final DocumentGenerationInputJpaRepository repository;
    private final DocumentTranscriptTextReader transcriptTextReader = new DocumentTranscriptTextReader();

    @Override
    public Optional<DocumentGenerationInputResult> find(
            long workspaceId,
            long transcriptId
    ) {
        return repository.findAvailableByWorkspaceIdAndId(
                workspaceId,
                transcriptId
        )
                .map(
                        transcript -> new DocumentGenerationInputResult(
                                transcript.getId(),
                                transcript.getRecordingSessionId(),
                                transcriptTextReader.read(transcript.getRawTranscriptJson())
                        )
                );
    }

    @Override
    public List<DocumentGenerationInputResult> findAll(
            long workspaceId,
            Collection<Long> transcriptIds
    ) {
        return repository.findAllAvailableByWorkspaceIdAndIdIn(
                workspaceId,
                transcriptIds
        )
                .stream()
                .map(
                        transcript -> new DocumentGenerationInputResult(
                                transcript.getId(),
                                transcript.getRecordingSessionId(),
                                transcriptTextReader.read(transcript.getRawTranscriptJson())
                        )
                )
                .toList();
    }

    @Override
    public Optional<DocumentGenerationInputResult> findForUpdate(
            long workspaceId,
            long transcriptId
    ) {
        return repository.findByWorkspaceIdAndId(
                workspaceId,
                transcriptId
        )
                .map(
                        transcript -> new DocumentGenerationInputResult(
                                transcript.getId(),
                                transcript.getRecordingSessionId(),
                                transcriptTextReader.read(transcript.getRawTranscriptJson())
                        )
                );
    }
}
