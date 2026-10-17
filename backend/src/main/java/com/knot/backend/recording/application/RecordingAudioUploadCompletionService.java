package com.knot.backend.recording.application;

import com.knot.backend.recording.application.dto.result.RecordingAudioUploadCompletionResult;
import com.knot.backend.recording.domain.StoredAudioObject;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class RecordingAudioUploadCompletionService {
    private final RecordingAudioUploadCompletionTransaction completionTransaction;
    private final RecordingAudioStorage audioStorage;
    private final RecordingTranscriptionService transcriptionService;

    public RecordingAudioUploadCompletionResult complete(
            long workspaceId,
            long memberId,
            long recordingId,
            long uploadId
    ) {
        RecordingLog.Scope prepare = RecordingLog.begin(
                "recording.upload.prepare",
                workspaceId,
                recordingId,
                uploadId,
                null
        );
        Optional<String> storageKey = prepare.call(() -> {
            Optional<String> found = completionTransaction.findStorageKeyToConfirm(
                    workspaceId,
                    memberId,
                    recordingId,
                    uploadId
            );
            prepare.outcome(found.isPresent() ? "prepared" : "replayed");
            return found;
        });
        StoredAudioObject storedObject = storageKey.map(key -> {
            RecordingLog.Scope verification = RecordingLog.begin(
                    "recording.upload.verify",
                    workspaceId,
                    recordingId,
                    uploadId,
                    null
            );
            return verification.call(() -> {
                StoredAudioObject found = audioStorage.findStoredObject(key);
                verification.outcome(found.exists() ? "found" : "missing");
                return found;
            });
        })
                .orElseGet(StoredAudioObject::missing);
        RecordingAudioUploadCompletionResult result = completionTransaction.complete(
                workspaceId,
                memberId,
                recordingId,
                uploadId,
                storedObject
        );
        transcriptionService.dispatch();
        return result;
    }
}
