package com.knot.backend.recording.infrastructure.persistence;

import com.knot.backend.recording.domain.model.RecordingAudioStream;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;

interface RecordingAudioStreamJpaRepository extends JpaRepository<RecordingAudioStream, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<RecordingAudioStream> findWithLockByRecordingId(long recordingId);

    Optional<RecordingAudioStream> findByRecordingId(long recordingId);
}
