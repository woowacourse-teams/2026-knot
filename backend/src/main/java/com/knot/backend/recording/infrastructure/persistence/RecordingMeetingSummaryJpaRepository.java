package com.knot.backend.recording.infrastructure.persistence;

import com.knot.backend.recording.domain.model.RecordingMeetingSummary;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

interface RecordingMeetingSummaryJpaRepository extends JpaRepository<RecordingMeetingSummary, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<RecordingMeetingSummary> findWithLockByRecordingId(long recordingId);

    Optional<RecordingMeetingSummary> findByRecordingId(long recordingId);
}
