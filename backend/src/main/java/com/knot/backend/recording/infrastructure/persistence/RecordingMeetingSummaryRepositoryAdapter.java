package com.knot.backend.recording.infrastructure.persistence;

import com.knot.backend.recording.domain.model.RecordingMeetingSummary;
import com.knot.backend.recording.domain.repository.RecordingMeetingSummaryRepository;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class RecordingMeetingSummaryRepositoryAdapter implements RecordingMeetingSummaryRepository {
    private static final String RECORDING_FROM = RecentRecordingQueryAdapter.OPENABLE_RECORDINGS + """
            JOIN workspaces w ON w.id=s.workspace_id AND w.deleted_at IS NULL
            """;
    private static final String READABLE = RecentRecordingQueryAdapter.AUDIO_SAVED;

    private final RecordingMeetingSummaryJpaRepository repository;
    private final JdbcClient jdbc;
    private long candidateCursor;

    @Override
    public void recordActivation(Instant now) {
        jdbc.sql(
                "INSERT INTO recording_meeting_summary_activation (id,activated_at) VALUES (1,:now) ON CONFLICT (id) DO NOTHING"
        )
                .param(
                        "now",
                        Timestamp.from(now)
                )
                .update();
    }

    @Override
    public synchronized int registerEligible(
            Instant now,
            int limit
    ) {
        List<SummaryInput> inputs = candidates(
                candidateCursor,
                limit
        );
        if (inputs.isEmpty() && candidateCursor != 0) {
            candidateCursor = 0;
            inputs = candidates(
                    0,
                    limit
            );
        }
        int inserted = 0;
        for (SummaryInput input : inputs) {
            if (hasText(input.content()))
                inserted += register(
                        input,
                        now
                );
            candidateCursor = input.batchId();
        }
        if (inputs.size() < limit)
            candidateCursor = 0;
        return inserted;
    }

    // One bounded page per pass. Wraparound reaches older batches that become
    // eligible later.
    private List<SummaryInput> candidates(
            long after,
            int limit
    ) {
        return jdbc.sql(
                "SELECT b.id AS batch_id,s.id AS recording_id,t.id AS transcript_id,t.content " + RECORDING_FROM + """
                        JOIN document_generation_batches b ON b.recording_session_id=s.id
                        JOIN transcripts t ON t.id=b.transcript_id AND t.recording_session_id=s.id
                        JOIN recording_meeting_summary_activation a ON a.id=1
                        WHERE s.ended_at>=a.activated_at AND b.id>:after AND
                        """ + READABLE + """
                        AND NOT EXISTS (SELECT 1 FROM recording_meeting_summaries g WHERE g.recording_id=s.id)
                        ORDER BY b.id LIMIT :limit
                        """
        )
                .param(
                        "after",
                        after
                )
                .param(
                        "limit",
                        limit
                )
                .query(
                        (
                                row,
                                index
                        ) -> new SummaryInput(
                                row.getLong("batch_id"),
                                row.getLong("recording_id"),
                                row.getLong("transcript_id"),
                                row.getString("content")
                        )
                )
                .list();
    }

    private int register(
            SummaryInput input,
            Instant now
    ) {
        return jdbc.sql("""
                INSERT INTO recording_meeting_summaries(recording_id,transcript_id,status,next_run_at,created_at)
                VALUES (:recordingId,:transcriptId,'QUEUED',:now,:now) ON CONFLICT (recording_id) DO NOTHING
                """)
                .param(
                        "recordingId",
                        input.recordingId()
                )
                .param(
                        "transcriptId",
                        input.transcriptId()
                )
                .param(
                        "now",
                        Timestamp.from(now)
                )
                .update();
    }

    private record SummaryInput(
            long batchId,
            long recordingId,
            long transcriptId,
            String content
    ) {
    }

    @Override
    public List<Long> findRunnable(
            Instant now,
            int limit
    ) {
        return jdbc.sql("""
                SELECT g.recording_id FROM recording_meeting_summaries g
                JOIN recording_sessions s ON s.id=g.recording_id
                JOIN workspaces w ON w.id=s.workspace_id AND w.deleted_at IS NULL
                WHERE (g.status='QUEUED' AND g.next_run_at<=:now) OR (g.status='RUNNING' AND g.lease_until<=:now)
                ORDER BY g.next_run_at,g.id LIMIT :limit
                """)
                .param(
                        "now",
                        Timestamp.from(now)
                )
                .param(
                        "limit",
                        limit
                )
                .query(Long.class)
                .list();
    }

    @Override
    public Optional<RecordingMeetingSummary> findByRecordingIdForUpdate(long recordingId) {
        return repository.findWithLockByRecordingId(recordingId);
    }

    @Override
    public Optional<RecordingMeetingSummary> findByRecordingId(long recordingId) {
        return repository.findByRecordingId(recordingId);
    }

    @Override
    public Optional<Long> findWorkspaceId(long recordingId) {
        return jdbc.sql("SELECT workspace_id FROM recording_sessions WHERE id=:id")
                .param(
                        "id",
                        recordingId
                )
                .query(Long.class)
                .optional();
    }

    @Override
    public boolean isReadable(long recordingId) {
        return jdbc.sql("SELECT count(*) " + RECORDING_FROM + " WHERE s.id=:id AND " + READABLE)
                .param(
                        "id",
                        recordingId
                )
                .query(Long.class)
                .single() == 1;
    }

    @Override
    public Optional<String> findUsableTranscript(
            long recordingId,
            long transcriptId
    ) {
        return jdbc.sql("SELECT t.content " + RECORDING_FROM + """
                JOIN document_generation_batches b ON b.recording_session_id=s.id
                JOIN transcripts t ON t.id=b.transcript_id AND t.recording_session_id=s.id
                WHERE s.id=:id AND t.id=:transcriptId AND
                """ + READABLE)
                .param(
                        "id",
                        recordingId
                )
                .param(
                        "transcriptId",
                        transcriptId
                )
                .query(String.class)
                .optional()
                .filter(this::hasText);
    }

    private boolean hasText(String value) {
        String text = new com.knot.backend.document.infrastructure.persistence.DocumentTranscriptTextReader()
                .read(value);
        return RecordingOutputQueryAdapter.hasText(text);
    }
}
