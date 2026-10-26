package com.knot.backend.recording.domain.repository;

import com.knot.backend.recording.domain.model.RecordingAudioUpload;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface RecordingAudioUploadRepository {

    RecordingAudioUpload save(RecordingAudioUpload recordingAudioUpload);

    Optional<RecordingAudioUpload> findById(long uploadId);

    Optional<RecordingAudioUpload> findByRecordingId(long recordingId);

    List<RecordingAudioUpload> findAllByRecordingIdIn(Collection<Long> recordingIds);

    Optional<RecordingAudioUpload> findByRecordingIdForUpdate(long recordingId);
}
