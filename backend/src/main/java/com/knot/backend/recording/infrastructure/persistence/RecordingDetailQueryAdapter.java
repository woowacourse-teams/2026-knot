package com.knot.backend.recording.infrastructure.persistence;

import com.knot.backend.recording.application.RecordingDetailQuery;
import com.knot.backend.recording.application.dto.result.RecordingDetailSnapshot;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class RecordingDetailQueryAdapter implements RecordingDetailQuery {
    private final RecordingDetailReadJpaRepository recordingDetailReadJpaRepository;

    @Override
    public Optional<RecordingDetailSnapshot> find(
            long workspaceId,
            long recordingId
    ) {
        return recordingDetailReadJpaRepository.findDetail(
                workspaceId,
                recordingId
        )
                .map(
                        row -> new RecordingDetailSnapshot(
                                row.memberId(),
                                row.status(),
                                row.currentIntervalStartedAt(),
                                row.accumulatedRecordingMillis(),
                                row.endedAt(),
                                row.endReason(),
                                row.audioUploadStatus(),
                                row.speakerCount()
                        )
                );
    }
}
