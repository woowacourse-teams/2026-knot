package com.knot.backend.recording.domain.repository;

import com.knot.backend.recording.domain.model.RecordingAudioUpload;

import java.util.Optional;

public interface RecordingAudioUploadRepository {

    RecordingAudioUpload save(RecordingAudioUpload recordingAudioUpload);

    Optional<RecordingAudioUpload> findById(long uploadId);

    Optional<RecordingAudioUpload> findByRecordingId(long recordingId);

    Optional<RecordingAudioUpload> findByRecordingIdForUpdate(long recordingId);
}
