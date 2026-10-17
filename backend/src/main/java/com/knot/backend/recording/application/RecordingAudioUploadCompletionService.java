package com.knot.backend.recording.application;

import com.knot.backend.recording.application.dto.result.RecordingAudioUploadCompletionResult;
import com.knot.backend.recording.domain.model.StoredAudioObject;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class RecordingAudioUploadCompletionService {
    private final RecordingAudioUploadService recordingAudioUploadService;
    private final RecordingAudioStorage recordingAudioStorage;
    private final RecordingTranscriptionService recordingTranscriptionService;

    public RecordingAudioUploadCompletionResult complete(
            long workspaceId,
            long memberId,
            long recordingId,
            long uploadId
    ) {
        StoredAudioObject storedObject = recordingAudioUploadService.findStorageKeyToConfirm(
                workspaceId,
                memberId,
                recordingId,
                uploadId
        )
                .map(recordingAudioStorage::findStoredObject)
                .orElseGet(StoredAudioObject::missing);
        RecordingAudioUploadCompletionResult result = recordingAudioUploadService.complete(
                workspaceId,
                memberId,
                recordingId,
                uploadId,
                storedObject
        );
        recordingTranscriptionService.dispatch();
        return result;
    }
}
