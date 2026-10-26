package com.knot.backend.recording.infrastructure.persistence;

import com.knot.backend.recording.domain.repository.IncrementalTranscriptionQueue;
import com.knot.backend.recording.domain.model.RecordingTranscriptionInput;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class IncrementalTranscriptionQueueAdapter implements IncrementalTranscriptionQueue {
    private final JdbcClient jdbc;

    @Override
    public List<Long> findRecordings(
            long afterId,
            int limit
    ) {
        return jdbc.sql("""
                SELECT s.id FROM recording_sessions s
                WHERE (s.incremental_pipeline_version IS NOT NULL OR EXISTS (
                    SELECT 1 FROM recording_transcription_inputs i WHERE i.recording_id=s.id AND i.status='READY'))
                    AND s.id > :afterId
                    AND s.status <> 'DISCARDED'
                    AND NOT EXISTS (SELECT 1 FROM recording_audio_cleanup c
                        WHERE c.recording_id=s.id AND c.scope='ALL')
                    AND NOT EXISTS (SELECT 1 FROM recording_transcriptions t
                        WHERE t.recording_session_id=s.id AND t.status IN ('SUCCEEDED','FAILED'))
                ORDER BY s.id LIMIT :limit
                """)
                .param(
                        "afterId",
                        afterId
                )
                .param(
                        "limit",
                        limit
                )
                .query(Long.class)
                .list();
    }

    /**
     * 끝난 녹음은 종료 시각이 이른 녹음부터 남은 구간을 모두 처리한다. 녹음 중인 녹음은 오래 기다린 녹음부터 돌아가며 처리한다. 근거는
     * ADR 1006이다.
     */
    @Override
    public List<WindowTask> findCandidates(
            Instant now,
            int limit
    ) {
        return jdbc.sql("""
                WITH recent_claims AS (
                    SELECT s.incremental_pipeline_version IS NOT NULL AS live_pipeline
                    FROM recording_transcription_windows history
                    JOIN recording_sessions s ON s.id=history.recording_id
                    WHERE history.last_claimed_at IS NOT NULL
                    ORDER BY history.last_claimed_at DESC,history.id DESC LIMIT 2
                ), service_turn AS (
                    SELECT COUNT(*)=2 AND COALESCE(BOOL_AND(live_pipeline),FALSE) AS prepared_first
                    FROM recent_claims
                )
                SELECT w.recording_id,w.id FROM recording_transcription_windows w
                JOIN recording_sessions s ON s.id=w.recording_id
                CROSS JOIN service_turn turn
                WHERE (s.incremental_pipeline_version=w.pipeline_version OR EXISTS (
                    SELECT 1 FROM recording_transcription_inputs i WHERE i.recording_id=s.id
                        AND w.pipeline_version IN (:preparedPipelines) AND i.status='READY'))
                    AND w.status IN ('QUEUED','PROCESSING')
                    AND NOT EXISTS (SELECT 1 FROM recording_audio_cleanup c
                        WHERE c.recording_id=s.id AND c.scope='ALL')
                    AND (w.status='PROCESSING' OR (w.next_run_at <= :now
                        AND NOT EXISTS (SELECT 1 FROM recording_transcriptions t
                            WHERE t.recording_session_id=s.id AND t.status IN ('SUCCEEDED','FAILED'))))
                ORDER BY CASE WHEN w.status='PROCESSING' THEN 0 ELSE 1 END,
                    CASE WHEN (s.incremental_pipeline_version IS NULL)=turn.prepared_first THEN 0 ELSE 1 END,
                    CASE WHEN s.status='ENDED' THEN 0 ELSE 1 END,
                    CASE WHEN s.status='ENDED' THEN s.ended_at END ASC,
                    (SELECT MAX(history.last_claimed_at) FROM recording_transcription_windows history
                        WHERE history.recording_id=w.recording_id AND history.last_claimed_at IS NOT NULL)
                        ASC NULLS FIRST,
                    w.recording_id,w.window_index,w.next_run_at,w.id LIMIT :limit
                """)
                .param(
                        "preparedPipelines",
                        RecordingTranscriptionInput.windowPipelineVersions()
                )
                .param(
                        "now",
                        java.sql.Timestamp.from(now)
                )
                .param(
                        "limit",
                        limit
                )
                .query(
                        (
                                rs,
                                row
                        ) -> new WindowTask(
                                rs.getLong(1),
                                rs.getLong(2)
                        )
                )
                .list();
    }

    @Override
    public boolean hasProcessingWindow() {
        return jdbc.sql("SELECT EXISTS(SELECT 1 FROM recording_transcription_windows WHERE status='PROCESSING')")
                .query(Boolean.class)
                .single();
    }
}
