CREATE TABLE recording_meeting_summary_activation (
    id SMALLINT PRIMARY KEY DEFAULT 1,
    activated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT chk_meeting_summary_activation_single CHECK (id = 1)
);

CREATE TABLE recording_meeting_summaries (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    recording_id BIGINT NOT NULL,
    transcript_id BIGINT NOT NULL,
    status VARCHAR(20) NOT NULL,
    content TEXT,
    attempt_no BIGINT NOT NULL DEFAULT 0,
    user_retry_count BIGINT NOT NULL DEFAULT 0,
    automatic_retry_count INTEGER NOT NULL DEFAULT 0,
    claim_token UUID,
    lease_until TIMESTAMPTZ,
    next_run_at TIMESTAMPTZ NOT NULL,
    failure_code VARCHAR(64),
    created_at TIMESTAMPTZ NOT NULL,
    started_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    CONSTRAINT fk_meeting_summaries_recording FOREIGN KEY (recording_id) REFERENCES recording_sessions(id) ON DELETE RESTRICT,
    CONSTRAINT fk_meeting_summaries_transcript FOREIGN KEY (transcript_id) REFERENCES transcripts(id) ON DELETE RESTRICT,
    CONSTRAINT uk_meeting_summaries_recording UNIQUE (recording_id),
    CONSTRAINT chk_meeting_summaries_status CHECK (status IN ('QUEUED', 'RUNNING', 'SUCCEEDED', 'FAILED')),
    CONSTRAINT chk_meeting_summaries_content CHECK ((status = 'SUCCEEDED') = (content IS NOT NULL)
        AND (content IS NULL OR btrim(content) <> '')),
    CONSTRAINT chk_meeting_summaries_lease CHECK ((status = 'RUNNING') = (claim_token IS NOT NULL)
        AND (status = 'RUNNING') = (lease_until IS NOT NULL)),
    CONSTRAINT chk_meeting_summaries_attempts CHECK (attempt_no >= 0 AND user_retry_count >= 0
        AND automatic_retry_count BETWEEN 0 AND 1)
);

CREATE INDEX idx_meeting_summaries_due ON recording_meeting_summaries(next_run_at, id)
    WHERE status IN ('QUEUED', 'RUNNING');
