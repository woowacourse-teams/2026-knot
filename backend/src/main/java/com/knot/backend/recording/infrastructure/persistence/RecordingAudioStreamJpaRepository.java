package com.knot.backend.recording.infrastructure.persistence;

import com.knot.backend.recording.domain.model.RecordingAudioStream;
import java.util.Optional;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.JpaRepository;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;

interface RecordingAudioStreamJpaRepository extends JpaRepository<RecordingAudioStream, Long> {

    @Query("""
            select s.recordingId from RecordingAudioStream s
            where s.sealedAt is not null and s.frozen = false
            order by s.sealedAt, s.recordingId
            """)
    List<Long> findSealedRecordingIds(Pageable pageable);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<RecordingAudioStream> findWithLockByRecordingId(long recordingId);

    Optional<RecordingAudioStream> findByRecordingId(long recordingId);
}
