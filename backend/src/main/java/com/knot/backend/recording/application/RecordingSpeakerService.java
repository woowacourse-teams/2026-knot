package com.knot.backend.recording.application;

import com.knot.backend.recording.application.dto.result.RecordingTranscriptResult;
import com.knot.backend.recording.domain.RecordingErrorCode;
import com.knot.backend.recording.domain.RecordingException;
import com.knot.backend.recording.domain.model.SpeakerDisplayName;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class RecordingSpeakerService {
    private final RecordingAccess access;
    private final RecentRecordingQuery recordings;
    private final RecordingTranscriptQuery transcripts;
    private final RecordingSpeakerNames names;

    @Transactional
    public String rename(
            long workspaceId,
            long memberId,
            long recordingId,
            int speakerNumber,
            String displayName
    ) {
        access.loadWorkspaceRecordingForUpdate(
                workspaceId,
                memberId,
                recordingId
        );
        if (!recordings.contains(
                workspaceId,
                recordingId
        )) {
            throw new RecordingException(RecordingErrorCode.RECORDING_NOT_FOUND);
        }
        if (speakerNumber <= 0) {
            throw new RecordingException(RecordingErrorCode.INVALID_RECORDING_DATA);
        }
        RecordingTranscriptResult transcript = transcripts.find(recordingId)
                .orElseThrow(() -> new RecordingException(RecordingErrorCode.RECORDING_TRANSCRIPT_NOT_FOUND));
        if (transcript.segments()
                .stream()
                .noneMatch(
                        segment -> Integer.valueOf(speakerNumber)
                                .equals(segment.speakerNumber())
                )) {
            throw new RecordingException(RecordingErrorCode.RECORDING_SPEAKER_NOT_FOUND);
        }
        String normalized = new SpeakerDisplayName(displayName).value();
        names.save(
                recordingId,
                speakerNumber,
                normalized
        );
        return normalized;
    }
}
