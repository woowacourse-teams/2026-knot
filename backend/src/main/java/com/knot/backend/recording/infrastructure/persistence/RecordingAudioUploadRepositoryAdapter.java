package com.knot.backend.recording.infrastructure.persistence;

import com.knot.backend.recording.domain.model.RecordingAudioUpload;
import com.knot.backend.recording.domain.repository.RecordingAudioUploadRepository;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class RecordingAudioUploadRepositoryAdapter implements RecordingAudioUploadRepository {
    private final RecordingAudioUploadJpaRepository recordingAudioUploadJpaRepository;

    @Override
    public RecordingAudioUpload save(RecordingAudioUpload recordingAudioUpload) {
        return recordingAudioUploadJpaRepository.saveAndFlush(recordingAudioUpload);
    }

    @Override
    public Optional<RecordingAudioUpload> findById(long uploadId) {
        return recordingAudioUploadJpaRepository.findById(uploadId);
    }

    @Override
    public Optional<RecordingAudioUpload> findByRecordingId(long recordingId) {
        return recordingAudioUploadJpaRepository.findByRecordingId(recordingId);
    }
    @Override
    public Optional<RecordingAudioUpload> findByRecordingIdForUpdate(long recordingId) {
        return recordingAudioUploadJpaRepository.findWithLockByRecordingId(recordingId);
    }
}
