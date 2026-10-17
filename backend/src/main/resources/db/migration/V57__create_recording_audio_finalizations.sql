CREATE TABLE recording_audio_finalizations (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    recording_id BIGINT NOT NULL UNIQUE REFERENCES recording_sessions(id) ON DELETE RESTRICT,
    status VARCHAR(20) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    next_run_at TIMESTAMPTZ NOT NULL,
    owner_token UUID,
    claim_epoch BIGINT NOT NULL DEFAULT 0,
    lease_until TIMESTAMPTZ,
    attempt INTEGER NOT NULL DEFAULT 0,
    failure_count INTEGER NOT NULL DEFAULT 0,
    failure_code VARCHAR(255),
    retryable BOOLEAN NOT NULL DEFAULT FALSE,
    manifest_json TEXT,
    manifest_hash VARCHAR(255),
    quality VARCHAR(255),
    final_key VARCHAR(255),
    final_version_id VARCHAR(255),
    final_checksum VARCHAR(255),
    final_byte_length BIGINT,
    ready_at TIMESTAMPTZ,
    CONSTRAINT chk_audio_finalization_status CHECK (status IN ('QUEUED','PROCESSING','READY','FAILED','DISCARDED')),
    CONSTRAINT chk_audio_finalization_lease CHECK (
        (status = 'PROCESSING' AND owner_token IS NOT NULL AND lease_until IS NOT NULL)
        OR (status <> 'PROCESSING' AND owner_token IS NULL AND lease_until IS NULL)),
    CONSTRAINT chk_audio_finalization_quality CHECK (quality IS NULL OR quality IN ('COMPLETE','PARTIAL')),
    CONSTRAINT chk_audio_finalization_ready CHECK (status <> 'READY' OR
        (manifest_json IS NOT NULL AND manifest_hash IS NOT NULL AND final_key IS NOT NULL
         AND final_version_id IS NOT NULL AND final_checksum IS NOT NULL AND final_byte_length >= 44 AND ready_at IS NOT NULL))
);
CREATE INDEX idx_audio_finalization_due ON recording_audio_finalizations(next_run_at, id)
    WHERE status IN ('QUEUED','PROCESSING');
