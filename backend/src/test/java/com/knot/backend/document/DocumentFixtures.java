package com.knot.backend.document;

import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

public class DocumentFixtures {
    public static final Instant CREATED_AT = Instant.parse("2026-10-06T00:00:00Z");

    private final JdbcClient jdbc;

    public DocumentFixtures(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public long saveCompletedTopicOperation(
            long workspaceId,
            long ownerId
    ) {
        return jdbc.sql("""
                INSERT INTO document_topic_operations(operation_id,workspace_id,requester_id,request_id,kind,status,
                    created_at,deadline_at,next_attempt_at,finished_at)
                VALUES (:operationId,:workspaceId,:ownerId,:requestId,'REORGANIZE','SUCCEEDED',
                    :time,:deadline,:time,:time) RETURNING id
                """)
                .param(
                        "operationId",
                        UUID.randomUUID()
                )
                .param(
                        "workspaceId",
                        workspaceId
                )
                .param(
                        "ownerId",
                        ownerId
                )
                .param(
                        "requestId",
                        UUID.randomUUID()
                )
                .param(
                        "time",
                        Timestamp.from(CREATED_AT)
                )
                .param(
                        "deadline",
                        Timestamp.from(CREATED_AT.plusSeconds(1800))
                )
                .query(Long.class)
                .single();
    }

    public long saveMember(String nickname) {
        return jdbc.sql("INSERT INTO members (nickname) VALUES (:nickname) RETURNING id")
                .param(
                        "nickname",
                        nickname
                )
                .query(Long.class)
                .single();
    }

    public long saveWorkspace() {
        return jdbc.sql("INSERT INTO workspaces (name, created_at) VALUES ('문서 팀', :time) RETURNING id")
                .param(
                        "time",
                        Timestamp.from(CREATED_AT)
                )
                .query(Long.class)
                .single();
    }

    public void join(
            long workspaceId,
            long memberId
    ) {
        jdbc.sql("""
                INSERT INTO workspace_members (workspace_id, member_id, role, joined_at)
                VALUES (:workspaceId, :memberId, 'MEMBER', :time)
                """)
                .param(
                        "workspaceId",
                        workspaceId
                )
                .param(
                        "memberId",
                        memberId
                )
                .param(
                        "time",
                        Timestamp.from(CREATED_AT)
                )
                .update();
    }

    public void leave(
            long workspaceId,
            long memberId
    ) {
        jdbc.sql("""
                UPDATE workspace_members SET left_at = :time, last_viewed = false
                WHERE workspace_id = :workspaceId AND member_id = :memberId AND left_at IS NULL
                """)
                .param(
                        "workspaceId",
                        workspaceId
                )
                .param(
                        "memberId",
                        memberId
                )
                .param(
                        "time",
                        Timestamp.from(CREATED_AT.plusSeconds(60))
                )
                .update();
    }

    public long saveRecording(
            long workspaceId,
            long memberId,
            long durationMillis
    ) {
        return jdbc.sql("""
                INSERT INTO recording_sessions (workspace_id, member_id, request_id, tab_id, control_token_hash,
                    status, started_at, ended_at, last_seen_at, accumulated_recording_millis)
                VALUES (:workspaceId, :memberId, :requestId, :tabId, :hash, 'ENDED', :start, :end, :end, :duration)
                RETURNING id
                """)
                .param(
                        "workspaceId",
                        workspaceId
                )
                .param(
                        "memberId",
                        memberId
                )
                .param(
                        "requestId",
                        UUID.randomUUID()
                )
                .param(
                        "tabId",
                        UUID.randomUUID()
                )
                .param(
                        "hash",
                        "a".repeat(64)
                )
                .param(
                        "start",
                        Timestamp.from(CREATED_AT.minusSeconds(3600))
                )
                .param(
                        "end",
                        Timestamp.from(CREATED_AT)
                )
                .param(
                        "duration",
                        durationMillis
                )
                .query(Long.class)
                .single();
    }

    public long saveTranscript(long recordingId) {
        return jdbc.sql("""
                INSERT INTO transcripts (recording_session_id, content, created_at)
                VALUES (:recordingId, '전체 녹음 원문', :time) RETURNING id
                """)
                .param(
                        "recordingId",
                        recordingId
                )
                .param(
                        "time",
                        Timestamp.from(CREATED_AT)
                )
                .query(Long.class)
                .single();
    }

    public long saveJob(
            long transcriptId,
            String status
    ) {
        Instant failedAt = null;
        if ("FAILED".equals(status)) {
            failedAt = CREATED_AT;
        }
        return saveJob(
                transcriptId,
                status,
                CREATED_AT,
                failedAt
        );
    }

    public long saveJob(
            long transcriptId,
            String status,
            Instant createdAt,
            Instant failedAt
    ) {
        long batchId = saveGenerationBatch(transcriptId);
        String topic = "테스트 주제 " + UUID.randomUUID();
        saveRegisteredTopic(
                batchId,
                topic
        );
        Instant updatedAt = createdAt;
        Instant expiresAt = null;
        if (failedAt != null) {
            updatedAt = failedAt;
            expiresAt = failedAt.plusSeconds(7 * 24 * 60 * 60);
        }
        long jobId = jdbc
                .sql(
                        """
                                INSERT INTO document_generation_jobs (batch_id, stage, topic, transcript_id, status, created_at, updated_at, last_failed_at, expires_at,
                                    next_attempt_at, execution_deadline_at, failure_cause)
                                VALUES (:batchId, 'GENERATION', :topic, :transcriptId, :status, :createdAt, :updatedAt, :failedAt, :expiresAt,
                                    CASE WHEN :status = 'QUEUED' THEN CAST(:updatedAt AS timestamptz) END,
                                    CASE WHEN :status = 'RUNNING' THEN CAST(:updatedAt AS timestamptz) + INTERVAL '150 seconds' END,
                                    CASE WHEN :status = 'FAILED' THEN 'LEGACY_FAILURE' END) RETURNING id
                                """
                )
                .param(
                        "batchId",
                        batchId
                )
                .param(
                        "topic",
                        topic
                )
                .param(
                        "transcriptId",
                        transcriptId
                )
                .param(
                        "status",
                        status
                )
                .param(
                        "createdAt",
                        Timestamp.from(createdAt)
                )
                .param(
                        "updatedAt",
                        Timestamp.from(updatedAt)
                )
                .param(
                        "failedAt",
                        nullableTimestamp(failedAt),
                        Types.TIMESTAMP
                )
                .param(
                        "expiresAt",
                        nullableTimestamp(expiresAt),
                        Types.TIMESTAMP
                )
                .query(Long.class)
                .single();
        synchronizeBatch(batchId);
        return jobId;
    }

    public void synchronizeBatch(long batchId) {
        jdbc.sql("""
                UPDATE document_generation_batches b SET
                    queued_count = c.queued, running_count = c.running,
                    succeeded_count = c.succeeded, failed_count = c.failed,
                    processing_status = CASE WHEN c.running > 0 THEN 'RUNNING' WHEN c.queued > 0 THEN 'QUEUED'
                        WHEN c.failed > 0 THEN 'FAILED' ELSE 'SUCCEEDED' END,
                    finished_at = CASE WHEN c.running + c.queued = 0 THEN c.changed_at END
                FROM (SELECT count(*) FILTER (WHERE status = 'QUEUED') AS queued,
                    count(*) FILTER (WHERE status = 'RUNNING') AS running,
                    count(*) FILTER (WHERE status = 'SUCCEEDED') AS succeeded,
                    count(*) FILTER (WHERE status = 'FAILED') AS failed, max(updated_at) AS changed_at
                    FROM document_generation_jobs WHERE batch_id = :id AND stage = 'GENERATION') c
                WHERE b.id = :id
                """)
                .param(
                        "id",
                        batchId
                )
                .update();
    }

    public long saveGenerationBatch(long transcriptId) {
        return jdbc
                .sql(
                        """
                                INSERT INTO document_generation_batches
                                    (recording_session_id, transcript_id, topic_registration_state, accepted_at, registered_at)
                                SELECT recording_session_id, id, 'TOPICS_REGISTERED', created_at, created_at FROM transcripts WHERE id = :id
                                ON CONFLICT (recording_session_id) DO UPDATE SET recording_session_id = EXCLUDED.recording_session_id
                                RETURNING id
                                """
                )
                .param(
                        "id",
                        transcriptId
                )
                .query(Long.class)
                .single();
    }

    public void saveRegisteredTopic(
            long batchId,
            String topic
    ) {
        jdbc.sql("""
                INSERT INTO document_generation_batch_topics (batch_id, position, topic)
                SELECT :batchId, count(*), :topic FROM document_generation_batch_topics WHERE batch_id = :batchId
                """)
                .param(
                        "batchId",
                        batchId
                )
                .param(
                        "topic",
                        topic
                )
                .update();
    }

    public long saveDocument(
            long workspaceId,
            long recordingId,
            long transcriptId,
            long jobId,
            String topic
    ) {
        return jdbc.sql("""
                INSERT INTO documents (workspace_id, recording_session_id, source_transcript_id,
                    document_generation_job_id, topic, title, content, status, created_at)
                VALUES (:workspaceId, :recordingId, :transcriptId, :jobId, :topic,
                    '문서 보관 정책', '# 읽기 전용 본문', 'DRAFT', :time) RETURNING id
                """)
                .param(
                        "workspaceId",
                        workspaceId
                )
                .param(
                        "recordingId",
                        recordingId
                )
                .param(
                        "transcriptId",
                        transcriptId
                )
                .param(
                        "jobId",
                        jobId
                )
                .param(
                        "topic",
                        topic
                )
                .param(
                        "time",
                        Timestamp.from(CREATED_AT)
                )
                .query(Long.class)
                .single();
    }

    public void target(
            long documentId,
            long memberId,
            Instant confirmedAt
    ) {
        jdbc.sql("""
                INSERT INTO document_confirmations (document_id, member_id, confirmed_at)
                VALUES (:documentId, :memberId, :confirmedAt)
                """)
                .param(
                        "documentId",
                        documentId
                )
                .param(
                        "memberId",
                        memberId
                )
                .param(
                        "confirmedAt",
                        nullableTimestamp(confirmedAt)
                )
                .update();
    }

    public String snapshot() {
        return jdbc.sql("""
                SELECT jsonb_build_object(
                    'documents', (SELECT jsonb_agg(to_jsonb(d) ORDER BY id) FROM documents d),
                    'confirmations', (SELECT jsonb_agg(to_jsonb(c) ORDER BY document_id, member_id)
                        FROM document_confirmations c))::text
                """)
                .query(String.class)
                .single();
    }

    private Timestamp nullableTimestamp(Instant instant) {
        if (instant == null) {
            return null;
        }
        return Timestamp.from(instant);
    }
}
