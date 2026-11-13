package com.knot.backend.recording.infrastructure.persistence;

import com.knot.backend.recording.domain.model.RecordingSession;
import java.util.Optional;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;

interface RecordingDetailReadJpaRepository extends Repository<RecordingSession, Long> {

    @Query("""
            select new com.knot.backend.recording.infrastructure.persistence.RecordingDetailRow(
                rs.memberId, rs.status, rs.currentIntervalStartedAt,
                rs.accumulatedRecordingMillis, rs.endedAt, rs.endReason, u.status, rs.speakerCount
            )
            from RecordingSession rs
            left join RecordingAudioUpload u on u.recordingId = rs.id
            where rs.workspaceId = :workspaceId and rs.id = :recordingId
            """)
    Optional<RecordingDetailRow> findDetail(
            long workspaceId,
            long recordingId
    );
}
