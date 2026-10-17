package com.knot.backend.recording.application;

import com.knot.backend.global.config.TranscriptionProperties;
import com.knot.backend.recording.application.dto.result.RecordingAudioUploadCompletionResult;
import com.knot.backend.recording.domain.model.RecordingAudioUpload;
import com.knot.backend.recording.domain.model.RecordingSession;
import com.knot.backend.recording.domain.repository.RecordingAudioUploadRepository;
import com.knot.backend.recording.domain.RecordingErrorCode;
import com.knot.backend.recording.domain.RecordingException;
import com.knot.backend.recording.domain.model.StoredAudioObject;
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
    private final RecordingTranscriptionStateService recordingTranscriptionStateService;
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
        RecordingAudioUpload upload = findValidatedUpload(
                workspaceId,
                memberId,
                recordingId,
                uploadId
        );
        upload.complete(
                storedObject,
                clock.instant()
                        .truncatedTo(ChronoUnit.MICROS)
        );
        recordingAudioUploadRepository.save(upload);
        if (transcriptionProperties.enabled() && upload.isAudioAvailable()) {
            recordingTranscriptionStateService.registerAutomatic(recordingId);
        }
        return RecordingAudioUploadCompletionResult.from(upload);
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
        RecordingSession session = recordingAccess.loadOwnedForUpdate(
                workspaceId,
                memberId,
                recordingId
        );
        session.validateLegacyAudioMode();
        session.validateAudioUploadable();
        RecordingAudioUpload upload = recordingAudioUploadRepository.findById(uploadId)
                .orElseThrow(() -> new RecordingException(RecordingErrorCode.AUDIO_UPLOAD_NOT_FOUND));
        upload.validateBelongsTo(recordingId);
        return upload;
    }
}
