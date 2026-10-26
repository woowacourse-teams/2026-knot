package com.knot.backend.recording.application;

import com.knot.backend.recording.application.dto.result.RecordingTranscriptResult;
import com.knot.backend.recording.domain.RecordingErrorCode;
import com.knot.backend.recording.domain.RecordingException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class RecordingTranscriptService {
    private final RecordingAccess recordingAccess;
    private final RecentRecordingQuery recentRecordingQuery;
    private final RecordingTranscriptQuery recordingTranscriptQuery;

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public RecordingTranscriptResult find(
            long workspaceId,
            long memberId,
            long recordingId
    ) {
        recordingAccess.validateMember(
                workspaceId,
                memberId
        );
        validateOpenable(
                workspaceId,
                recordingId
        );
        return recordingTranscriptQuery.find(recordingId)
                .orElseThrow(() -> new RecordingException(RecordingErrorCode.RECORDING_TRANSCRIPT_NOT_FOUND));
    }

    private void validateOpenable(
            long workspaceId,
            long recordingId
    ) {
        if (recordingId <= 0) {
            throw new RecordingException(RecordingErrorCode.INVALID_RECORDING_DATA);
        }
        if (!recentRecordingQuery.contains(
                workspaceId,
                recordingId
        )) {
            throw new RecordingException(RecordingErrorCode.RECORDING_NOT_FOUND);
        }
    }
}
