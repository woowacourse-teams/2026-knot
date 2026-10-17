package com.knot.backend.recording.application;

import com.knot.backend.global.config.TranscriptionProperties;
import com.knot.backend.recording.application.dto.result.RecordingAudioUploadCompletionResult;
import com.knot.backend.recording.domain.RecordingAudioUpload;
import com.knot.backend.recording.domain.RecordingAudioUploadRepository;
import com.knot.backend.recording.domain.RecordingErrorCode;
import com.knot.backend.recording.domain.RecordingException;
import com.knot.backend.recording.domain.StoredAudioObject;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
public class RecordingAudioUploadCompletionTransaction {
    private final RecordingAccess recordingAccess;
    private final RecordingAudioUploadRepository recordingAudioUploadRepository;
    private final Clock clock;
    private final RecordingTranscriptionStateService transcriptionState;
    private final TranscriptionProperties transcriptionProperties;

    @Transactional
    public Optional<String> findStorageKeyToConfirm(
            long workspaceId,
            long memberId,
            long recordingId,
            long uploadId
    ) {
        return findValidatedUpload(
                workspaceId,
                memberId,
                recordingId,
                uploadId
        ).findStorageKeyToConfirm();
    }

    @Transactional
    public RecordingAudioUploadCompletionResult complete(
            long workspaceId,
            long memberId,
            long recordingId,
            long uploadId,
            StoredAudioObject storedObject
    ) {
        RecordingLog.Scope trace = RecordingLog.begin(
                "recording.upload.complete",
                workspaceId,
                recordingId,
                uploadId,
                null
        );
        return trace.call(() -> {
            RecordingAudioUpload upload = findValidatedUpload(
                    workspaceId,
                    memberId,
                    recordingId,
                    uploadId
            );
            trace.outcome(upload.isCompleted() ? "replayed" : "completed");
            upload.complete(
                    storedObject,
                    clock.instant()
                            .truncatedTo(ChronoUnit.MICROS)
            );
            recordingAudioUploadRepository.save(upload);
            if (transcriptionProperties.enabled() && upload.isAudioAvailable()) {
                transcriptionState.registerAutomatic(
                        workspaceId,
                        recordingId
                );
            }
            return RecordingAudioUploadCompletionResult.from(upload);
        });
    }

    private RecordingAudioUpload findValidatedUpload(
            long workspaceId,
            long memberId,
            long recordingId,
            long uploadId
    ) {
        if (uploadId <= 0) {
            throw new RecordingException(RecordingErrorCode.INVALID_RECORDING_DATA);
        }
        recordingAccess.loadOwnedForUpdate(
                workspaceId,
                memberId,
                recordingId
        )
                .validateAudioUploadable();
        RecordingAudioUpload upload = recordingAudioUploadRepository.findById(uploadId)
                .orElseThrow(() -> new RecordingException(RecordingErrorCode.AUDIO_UPLOAD_NOT_FOUND));
        upload.validateBelongsTo(recordingId);
        return upload;
    }
}
