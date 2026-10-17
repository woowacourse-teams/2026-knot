-- V50/V51 기록은 유지하고 표준 DB와 과거 수동 대기열 DB에 같은 실행 계약을 적용한다.
ALTER TABLE recording_transcriptions
    ADD COLUMN IF NOT EXISTS queued_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS finished_at TIMESTAMPTZ,
    ADD COLUMN owner_token UUID,
    ADD COLUMN claim_epoch BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN lease_until TIMESTAMPTZ,
    ADD COLUMN next_run_at TIMESTAMPTZ,
    ADD COLUMN execution_deadline TIMESTAMPTZ,
    ADD COLUMN readiness_started_at TIMESTAMPTZ,
    ADD COLUMN readiness_failures INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN remote_observation VARCHAR(30) NOT NULL DEFAULT 'NOT_SUBMITTED',
    ADD COLUMN source_storage_key TEXT,
    ADD COLUMN source_version_id TEXT,
    ADD COLUMN source_content_type VARCHAR(100),
    ADD COLUMN source_content_length BIGINT,
    ADD COLUMN speaker_count INTEGER;

UPDATE recording_transcriptions
SET queued_at = COALESCE(queued_at, started_at),
    next_run_at = COALESCE(queued_at, started_at),
    remote_observation = CASE WHEN status = 'PROCESSING' THEN 'UNCONFIRMED' ELSE 'NOT_SUBMITTED' END;

UPDATE recording_transcriptions t
SET source_storage_key = u.storage_key,
    source_version_id = u.storage_version_id,
    source_content_type = u.content_type,
    source_content_length = u.content_length
FROM recording_audio_uploads u WHERE u.recording_id = t.recording_session_id;

ALTER TABLE recording_transcriptions
    ALTER COLUMN queued_at SET NOT NULL,
    ALTER COLUMN queued_at SET DEFAULT CURRENT_TIMESTAMP,
    ALTER COLUMN next_run_at SET NOT NULL,
    ALTER COLUMN next_run_at SET DEFAULT CURRENT_TIMESTAMP,
    ALTER COLUMN started_at DROP NOT NULL,
    DROP CONSTRAINT IF EXISTS chk_recording_transcriptions_status,
    DROP CONSTRAINT IF EXISTS chk_recording_transcriptions_state,
    DROP CONSTRAINT IF EXISTS chk_recording_transcriptions_time,
    ADD CONSTRAINT chk_recording_transcriptions_status
        CHECK (status IN ('QUEUED', 'PROCESSING', 'SUCCEEDED', 'FAILED')),
    ADD CONSTRAINT chk_recording_transcriptions_state CHECK (
        (status IN ('QUEUED', 'PROCESSING', 'SUCCEEDED') AND failure_code IS NULL)
        OR (status = 'FAILED' AND failure_code IS NOT NULL AND length(trim(failure_code)) > 0)
    ),
    ADD CONSTRAINT chk_recording_transcriptions_owner CHECK (
        (owner_token IS NULL AND lease_until IS NULL)
        OR (status = 'PROCESSING' AND owner_token IS NOT NULL AND lease_until IS NOT NULL)
    ),
    ADD CONSTRAINT chk_recording_transcriptions_execution CHECK (
        claim_epoch >= 0 AND readiness_failures >= 0
        AND (speaker_count IS NULL OR speaker_count > 0)
        AND (source_content_length IS NULL OR source_content_length > 0)
        AND remote_observation IN ('NOT_SUBMITTED', 'NOT_ACCEPTED', 'UNCONFIRMED', 'PROCESSING',
                                  'SUCCEEDED', 'FAILED', 'NOT_FOUND')
    );

-- 과거 같은 이름의 인덱스 정의를 신뢰하지 않고 명시한 정의로 재구성한다.
DROP INDEX IF EXISTS idx_recording_transcriptions_queued;
DROP INDEX IF EXISTS uk_recording_transcriptions_processing;
CREATE INDEX idx_recording_transcriptions_queued
    ON recording_transcriptions (queued_at, id) WHERE status = 'QUEUED';
CREATE UNIQUE INDEX uk_recording_transcriptions_processing
    ON recording_transcriptions (status) WHERE status = 'PROCESSING';
