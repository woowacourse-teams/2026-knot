-- #981: 성공 전사로 만든 AI 녹음 제목과 그 생성 작업을 녹음마다 하나 둔다.
CREATE TABLE recording_title_generations (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    recording_id BIGINT NOT NULL REFERENCES recording_sessions(id) ON DELETE RESTRICT,
    status VARCHAR(20) NOT NULL,
    title VARCHAR(100),
    attempt_count INTEGER NOT NULL DEFAULT 0,
    next_run_at TIMESTAMPTZ NOT NULL,
    lease_until TIMESTAMPTZ,
    failure_code VARCHAR(64),
    created_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ,
    CONSTRAINT uk_recording_title_generations_recording UNIQUE (recording_id),
    CONSTRAINT chk_recording_title_generations_status
        CHECK (status IN ('QUEUED', 'RUNNING', 'SUCCEEDED', 'FAILED')),
    CONSTRAINT chk_recording_title_generations_title
        CHECK ((status = 'SUCCEEDED') = (title IS NOT NULL) AND (title IS NULL OR btrim(title) <> '')),
    CONSTRAINT chk_recording_title_generations_lease
        CHECK ((status = 'RUNNING') = (lease_until IS NOT NULL)),
    CONSTRAINT chk_recording_title_generations_attempts CHECK (attempt_count >= 0)
);

CREATE INDEX idx_recording_title_generations_due
    ON recording_title_generations (next_run_at, id)
    WHERE status IN ('QUEUED', 'RUNNING');

-- 최근 녹음 목록의 Workspace별 시작 시각·ID 내림차순 커서 조회
CREATE INDEX idx_recording_sessions_workspace_ended_recent
    ON recording_sessions (workspace_id, started_at DESC, id DESC)
    WHERE status = 'ENDED';
