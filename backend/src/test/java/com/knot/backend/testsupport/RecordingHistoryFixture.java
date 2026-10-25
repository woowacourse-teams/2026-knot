package com.knot.backend.testsupport;

import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

public class RecordingHistoryFixture {
    public static final Instant CREATED_AT = Instant.parse("2026-10-20T00:00:00Z");
    private static final String CONTROL_TOKEN_HASH = "a".repeat(64);

    private final JdbcClient jdbcClient;

    public RecordingHistoryFixture(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public void clear() {
        jdbcClient.sql("""
                TRUNCATE TABLE recording_sessions, workspace_invitations, workspace_members, workspaces,
                    oauth_identities, members
                RESTART IDENTITY CASCADE
                """)
                .update();
    }

    public long member(String nickname) {
        return jdbcClient.sql("INSERT INTO members (nickname) VALUES (:nickname) RETURNING id")
                .param(
                        "nickname",
                        nickname
                )
                .query(Long.class)
                .single();
    }

    public long workspace(String name) {
        return jdbcClient.sql("INSERT INTO workspaces (name, created_at) VALUES (:name, :createdAt) RETURNING id")
                .param(
                        "name",
                        name
                )
                .param(
                        "createdAt",
                        Timestamp.from(CREATED_AT)
                )
                .query(Long.class)
                .single();
    }

    public void join(
            long workspaceId,
            long memberId
    ) {
        jdbcClient.sql("""
                INSERT INTO workspace_members (workspace_id, member_id, role, joined_at)
                VALUES (:workspaceId, :memberId, 'MEMBER', :joinedAt)
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
                        "joinedAt",
                        Timestamp.from(CREATED_AT)
                )
                .update();
    }

    public long endedRecording(
            long workspaceId,
            long memberId,
            Instant startedAt,
            String audioMode
    ) {
        return jdbcClient.sql("""
                INSERT INTO recording_sessions (workspace_id, member_id, request_id, tab_id, control_token_hash,
                    status, audio_mode, started_at, ended_at, last_seen_at, end_reason, accumulated_recording_millis)
                VALUES (:workspaceId, :memberId, :requestId, :tabId, :hash, 'ENDED', :audioMode, :startedAt,
                    :endedAt, :endedAt, 'USER_ENDED', 61999)
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
                        CONTROL_TOKEN_HASH
                )
                .param(
                        "audioMode",
                        audioMode
                )
                .param(
                        "startedAt",
                        Timestamp.from(startedAt)
                )
                .param(
                        "endedAt",
                        Timestamp.from(startedAt.plusSeconds(120))
                )
                .query(Long.class)
                .single();
    }

    public long savedRecording(
            long workspaceId,
            long memberId,
            Instant startedAt
    ) {
        long recordingId = endedRecording(
                workspaceId,
                memberId,
                startedAt,
                "FINAL_WEBM_V1"
        );
        completedUpload(recordingId);
        return recordingId;
    }

    public void changeStatus(
            long recordingId,
            String status
    ) {
        String interval = "NULL";
        String endedAt = "ended_at";
        if ("RECORDING".equals(status)) {
            interval = "started_at";
        }
        if ("RECORDING".equals(status) || "PAUSED".equals(status)) {
            endedAt = "NULL";
        }
        jdbcClient
                .sql(
                        "UPDATE recording_sessions SET status=:status, end_reason=NULL, current_interval_started_at="
                                + interval + ", ended_at=" + endedAt + " WHERE id=:id"
                )
                .param(
                        "status",
                        status
                )
                .param(
                        "id",
                        recordingId
                )
                .update();
    }

    public void completedUpload(long recordingId) {
        upload(
                recordingId,
                "COMPLETED"
        );
    }

    public void reservedUpload(long recordingId) {
        upload(
                recordingId,
                "RESERVED"
        );
    }

    public void finalization(
            long recordingId,
            String status
    ) {
        boolean ready = "READY".equals(status);
        boolean processing = "PROCESSING".equals(status);
        jdbcClient.sql("""
                INSERT INTO recording_audio_finalizations (recording_id, status, created_at, next_run_at,
                    owner_token, lease_until, manifest_json, manifest_hash, final_key, final_version_id,
                    final_checksum, final_byte_length, ready_at)
                VALUES (:recordingId, :status, :createdAt, :createdAt, :owner, :leaseUntil, :manifest, :manifest,
                    :manifest, :manifest, :manifest, :byteLength, :readyAt)
                """)
                .param(
                        "recordingId",
                        recordingId
                )
                .param(
                        "status",
                        status
                )
                .param(
                        "createdAt",
                        Timestamp.from(CREATED_AT)
                )
                .param(
                        "owner",
                        processing ? UUID.randomUUID() : null,
                        Types.OTHER
                )
                .param(
                        "leaseUntil",
                        processing ? Timestamp.from(CREATED_AT.plusSeconds(60)) : null,
                        Types.TIMESTAMP
                )
                .param(
                        "manifest",
                        ready ? "[]" : null,
                        Types.VARCHAR
                )
                .param(
                        "byteLength",
                        ready ? 44L : null,
                        Types.BIGINT
                )
                .param(
                        "readyAt",
                        ready ? Timestamp.from(CREATED_AT) : null,
                        Types.TIMESTAMP
                )
                .update();
    }

    public void changeFinalization(
            long recordingId,
            String status
    ) {
        jdbcClient.sql("""
                UPDATE recording_audio_finalizations SET status=:status, manifest_json='[]', manifest_hash='h',
                    final_key='k', final_version_id='v', final_checksum='c', final_byte_length=44, ready_at=created_at
                WHERE recording_id=:recordingId
                """)
                .param(
                        "status",
                        status
                )
                .param(
                        "recordingId",
                        recordingId
                )
                .update();
    }

    public void transcriptionFailed(long recordingId) {
        jdbcClient.sql("""
                INSERT INTO recording_transcriptions (recording_session_id, attempt_id, status, failure_code)
                VALUES (:recordingId, :attemptId, 'FAILED', 'TEST_FAILURE')
                """)
                .param(
                        "recordingId",
                        recordingId
                )
                .param(
                        "attemptId",
                        UUID.randomUUID()
                )
                .update();
    }

    public long batch(
            long recordingId,
            String registrationState,
            String processingStatus
    ) {
        long transcriptId = jdbcClient.sql("""
                INSERT INTO transcripts (recording_session_id, content, created_at)
                VALUES (:recordingId, '회원 탈퇴 정책을 정했다.', :createdAt)
                RETURNING id
                """)
                .param(
                        "recordingId",
                        recordingId
                )
                .param(
                        "createdAt",
                        Timestamp.from(CREATED_AT)
                )
                .query(Long.class)
                .single();
        boolean registered = !"WAITING_CLASSIFICATION".equals(registrationState);
        boolean finished = !"QUEUED".equals(processingStatus) && !"RUNNING".equals(processingStatus);
        return jdbcClient.sql("""
                INSERT INTO document_generation_batches (recording_session_id, transcript_id, topic_registration_state,
                    accepted_at, registered_at, cleanup_requested_at, processing_status, finished_at)
                VALUES (:recordingId, :transcriptId, :state, :createdAt, :registeredAt, :cleanupRequestedAt,
                    :processingStatus, :finishedAt)
                RETURNING id
                """)
                .param(
                        "recordingId",
                        recordingId
                )
                .param(
                        "transcriptId",
                        transcriptId
                )
                .param(
                        "state",
                        registrationState
                )
                .param(
                        "createdAt",
                        Timestamp.from(CREATED_AT)
                )
                .param(
                        "registeredAt",
                        registered ? Timestamp.from(CREATED_AT) : null,
                        Types.TIMESTAMP
                )
                .param(
                        "cleanupRequestedAt",
                        "NO_CONTENT".equals(registrationState) ? Timestamp.from(CREATED_AT) : null,
                        Types.TIMESTAMP
                )
                .param(
                        "processingStatus",
                        processingStatus
                )
                .param(
                        "finishedAt",
                        finished ? Timestamp.from(CREATED_AT) : null,
                        Types.TIMESTAMP
                )
                .query(Long.class)
                .single();
    }

    public void failedJob(long batchId) {
        jdbcClient.sql("""
                INSERT INTO document_generation_jobs (transcript_id, batch_id, stage, status, created_at, updated_at,
                    last_failed_at, expires_at, failure_cause)
                SELECT transcript_id, id, 'CLASSIFICATION', 'FAILED', accepted_at, accepted_at, accepted_at,
                    accepted_at + INTERVAL '168 hours', 'INTERNAL'
                FROM document_generation_batches WHERE id=:batchId
                """)
                .param(
                        "batchId",
                        batchId
                )
                .update();
    }

    public void title(
            long recordingId,
            String title
    ) {
        jdbcClient.sql("""
                INSERT INTO recording_title_generations (recording_id, status, title, attempt_count, next_run_at,
                    created_at, completed_at)
                VALUES (:recordingId, 'SUCCEEDED', :title, 1, :createdAt, :createdAt, :createdAt)
                """)
                .param(
                        "recordingId",
                        recordingId
                )
                .param(
                        "title",
                        title
                )
                .param(
                        "createdAt",
                        Timestamp.from(CREATED_AT)
                )
                .update();
    }

    private void upload(
            long recordingId,
            String status
    ) {
        boolean completed = "COMPLETED".equals(status);
        jdbcClient.sql("""
                INSERT INTO recording_audio_uploads (recording_id, storage_key, content_type, content_length, status,
                    reserved_at, completed_at)
                VALUES (:recordingId, :storageKey, 'audio/webm', 100, :status, :reservedAt, :completedAt)
                """)
                .param(
                        "recordingId",
                        recordingId
                )
                .param(
                        "storageKey",
                        "recordings/" + recordingId + "/audio.webm"
                )
                .param(
                        "status",
                        status
                )
                .param(
                        "reservedAt",
                        Timestamp.from(CREATED_AT)
                )
                .param(
                        "completedAt",
                        completed ? Timestamp.from(CREATED_AT) : null,
                        Types.TIMESTAMP
                )
                .update();
    }
}
