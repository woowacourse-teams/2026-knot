package com.knot.backend.recording.application;

import com.knot.backend.recording.application.dto.command.RecordingAudioUploadUrlCommand;
import com.knot.backend.recording.application.dto.result.PresignedAudioUpload;
import com.knot.backend.recording.application.dto.result.RecordingAudioUploadUrlResult;
import com.knot.backend.recording.domain.RecordingAudioUpload;
import com.knot.backend.recording.domain.RecordingAudioUploadRepository;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class RecordingAudioUploadUrlService {
    private static final String STORAGE_KEY_PREFIX = "recordings/";

    private final RecordingAccess recordingAccess;
    private final RecordingAudioUploadRepository recordingAudioUploadRepository;
    private final RecordingAudioStorage audioStorage;
    private final Clock clock;

    @Transactional
    public RecordingAudioUploadUrlResult issue(
            long workspaceId,
            long memberId,
            long recordingId,
            RecordingAudioUploadUrlCommand command
    ) {
        RecordingLog.Scope trace = RecordingLog.begin(
                "recording.upload_url",
                workspaceId,
                recordingId,
                null,
                null
        );
        return trace.call(() -> {
            recordingAccess.loadOwnedForUpdate(
                    workspaceId,
                    memberId,
                    recordingId
            )
                    .validateAudioUploadable();

            Optional<RecordingAudioUpload> existing = recordingAudioUploadRepository.findByRecordingId(recordingId);
            existing.ifPresent(
                    reserved -> reserved.validateReissuable(
                            command.contentType(),
                            command.contentLength()
                    )
            );
            RecordingAudioUpload upload = existing.orElseGet(
                    () -> recordingAudioUploadRepository.save(
                            reserve(
                                    recordingId,
                                    command
                            )
                    )
            );
            trace.upload(upload.getId())
                    .outcome(existing.isEmpty() ? "issued" : "reissued");
            PresignedAudioUpload presigned = audioStorage.presignUpload(
                    upload.getStorageKey(),
                    upload.getContentType(),
                    upload.getContentLength()
            );
            return new RecordingAudioUploadUrlResult(
                    upload.getId(),
                    presigned.uploadUrl(),
                    presigned.expiresAt(),
                    existing.isEmpty()
            );
        });
    }

    private RecordingAudioUpload reserve(
            long recordingId,
            RecordingAudioUploadUrlCommand command
    ) {
        return RecordingAudioUpload.reserve(
                recordingId,
                STORAGE_KEY_PREFIX + recordingId + "/" + UUID.randomUUID(),
                command.contentType(),
                command.contentLength(),
                clock.instant()
                        .truncatedTo(ChronoUnit.MICROS)
        );
    }
}
