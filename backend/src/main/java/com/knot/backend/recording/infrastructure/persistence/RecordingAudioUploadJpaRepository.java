package com.knot.backend.recording.infrastructure.persistence;

import com.knot.backend.recording.domain.model.RecordingAudioUpload;
import java.util.Optional;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.JpaRepository;

interface RecordingAudioUploadJpaRepository extends JpaRepository<RecordingAudioUpload, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<RecordingAudioUpload> findWithLockByRecordingId(long recordingId);

    Optional<RecordingAudioUpload> findByRecordingId(long recordingId);
}
