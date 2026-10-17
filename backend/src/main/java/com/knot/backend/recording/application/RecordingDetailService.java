package com.knot.backend.recording.application;

import com.knot.backend.recording.application.dto.result.RecordingDetailResult;
import com.knot.backend.recording.application.dto.result.RecordingDetailSnapshot;
import com.knot.backend.recording.domain.RecordingErrorCode;
import com.knot.backend.recording.domain.RecordingException;
import com.knot.backend.document.application.DocumentGenerationProgressService;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;

@Service
@RequiredArgsConstructor
public class RecordingDetailService {
    private final RecordingAccess recordingAccess;
    private final RecordingDetailQuery recordingDetailQuery;
    private final RecordingTranscriptionStateService recordingTranscriptionStateService;
    private final DocumentGenerationProgressService documentGenerationProgressService;
    private final Clock clock;
    private final RecordingAudioFinalizationQuery audioQuality;

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public RecordingDetailResult find(
            long workspaceId,
            long memberId,
            long recordingId
    ) {
        recordingAccess.validateMember(
                workspaceId,
                memberId
        );
        if (recordingId <= 0) {
            throw new RecordingException(RecordingErrorCode.INVALID_RECORDING_DATA);
        }
        RecordingDetailSnapshot snapshot = recordingDetailQuery.find(
                workspaceId,
                recordingId
        )
                .orElseThrow(() -> new RecordingException(RecordingErrorCode.RECORDING_NOT_FOUND));
        if (snapshot.memberId() != memberId) {
            throw new RecordingException(RecordingErrorCode.RECORDING_CONTROL_DENIED);
        }
        Instant now = clock.instant()
                .truncatedTo(ChronoUnit.MICROS);
        return RecordingDetailResult.of(
                snapshot,
                recordingTranscriptionStateService.findSnapshot(recordingId),
                documentGenerationProgressService.findSummary(
                        workspaceId,
                        recordingId,
                        now
                )
                        .orElse(null),
                now
        )
                .withSourceAudio(audioQuality.sourceQuality(recordingId));
    }
}
