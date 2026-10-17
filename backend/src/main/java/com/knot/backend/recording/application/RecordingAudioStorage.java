package com.knot.backend.recording.application;

import com.knot.backend.recording.application.dto.result.PresignedAudioUpload;
import com.knot.backend.recording.domain.model.StoredAudioObject;

public interface RecordingAudioStorage {

    String presignDownload(
            String storageKey,
            String versionId,
            String contentType,
            long contentLength
    );

    PresignedAudioUpload presignUpload(
            String storageKey,
            String contentType,
            long contentLength
    );

    StoredAudioObject findStoredObject(String storageKey);

    void deleteStoredObject(String storageKey);

    void deleteRecordingPrefix(String storagePrefix);
}
