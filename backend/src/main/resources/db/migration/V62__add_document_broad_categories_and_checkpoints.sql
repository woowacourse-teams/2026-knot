CREATE TABLE document_categories (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    workspace_id BIGINT NOT NULL REFERENCES workspaces(id) ON DELETE RESTRICT,
    name VARCHAR(80) NOT NULL,
    normalized_name VARCHAR(80) NOT NULL,
    description VARCHAR(500) NOT NULL,
    kind VARCHAR(20) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uk_document_category_name UNIQUE (workspace_id, normalized_name),
    CONSTRAINT uk_document_category_workspace UNIQUE (id, workspace_id),
    CONSTRAINT ck_document_category_kind CHECK (
        (kind = 'NORMAL' AND name NOT IN ('기타', '미분류', '일반'))
        OR (kind = 'FALLBACK' AND name = '기타')
    ),
    CONSTRAINT ck_document_category_text CHECK (
        name !~ '^[[:space:]]*$' AND name = normalized_name AND description !~ '^[[:space:]]*$'
    )
);

CREATE UNIQUE INDEX uk_document_category_fallback
    ON document_categories(workspace_id) WHERE kind = 'FALLBACK';

ALTER TABLE documents
    ADD COLUMN category_id BIGINT,
    ADD COLUMN grouping_scheme VARCHAR(20) NOT NULL DEFAULT 'LEGACY',
    ADD COLUMN assignment_source VARCHAR(20),
    ADD COLUMN assignment_rule_version VARCHAR(40),
    ADD CONSTRAINT fk_document_category_workspace FOREIGN KEY (category_id, workspace_id)
        REFERENCES document_categories(id, workspace_id) ON DELETE RESTRICT,
    ADD CONSTRAINT ck_document_grouping CHECK ((
        (grouping_scheme = 'LEGACY' AND category_id IS NULL AND assignment_source IS NULL)
        OR (grouping_scheme = 'BROAD' AND category_id IS NOT NULL
            AND assignment_source IN ('AI_REUSE', 'AI_NEW', 'RULE_REUSE', 'RULE_NEW', 'FALLBACK')
            AND assignment_rule_version IS NOT NULL)
    ) IS TRUE);

CREATE INDEX idx_documents_workspace_category ON documents(workspace_id, category_id);

ALTER TABLE document_generation_jobs
    ADD COLUMN processing_phase VARCHAR(20) NOT NULL DEFAULT 'WRITE_BODY',
    ADD COLUMN category_attempt_count INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN phase_resume_count INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN category_started_at TIMESTAMPTZ,
    ADD COLUMN category_deadline_at TIMESTAMPTZ,
    ADD COLUMN category_last_failure VARCHAR(40),
    ADD CONSTRAINT ck_generation_phase CHECK (
        processing_phase IN ('WRITE_BODY', 'ASSIGN_CATEGORY', 'PUBLISH')
        AND (stage = 'GENERATION' OR processing_phase = 'WRITE_BODY')
    ),
    ADD CONSTRAINT ck_generation_category_budget CHECK ((
        category_attempt_count >= 0
        AND ((category_started_at IS NULL AND category_deadline_at IS NULL AND category_attempt_count = 0)
            OR (category_started_at IS NOT NULL AND category_deadline_at > category_started_at))
        AND (processing_phase = 'WRITE_BODY' OR category_started_at IS NOT NULL)
    ) IS TRUE);

ALTER TABLE document_generation_jobs
    DROP CONSTRAINT chk_document_generation_jobs_attempt_counts,
    ADD CONSTRAINT chk_document_generation_jobs_attempt_counts CHECK (
        attempt_count >= 1 AND user_retry_count BETWEEN 0 AND 3
        AND automatic_retry_count >= 0 AND phase_resume_count >= 0
        AND attempt_count::BIGINT = 1::BIGINT + user_retry_count::BIGINT
            + automatic_retry_count::BIGINT + phase_resume_count::BIGINT
    );

CREATE TABLE prepared_documents (
    job_id BIGINT PRIMARY KEY,
    workspace_id BIGINT NOT NULL,
    transcript_id BIGINT NOT NULL,
    recording_session_id BIGINT NOT NULL,
    title TEXT NOT NULL CHECK (title !~ '^[[:space:]]*$'),
    summary TEXT,
    content TEXT NOT NULL CHECK (content !~ '^[[:space:]]*$'),
    existing_category_id BIGINT,
    new_category_name VARCHAR(80),
    new_category_description VARCHAR(500),
    assignment_source VARCHAR(20),
    catalog_version VARCHAR(64),
    rule_version VARCHAR(40),
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_prepared_job_input FOREIGN KEY (job_id, transcript_id)
        REFERENCES document_generation_jobs(id, transcript_id) ON DELETE CASCADE,
    CONSTRAINT fk_prepared_transcript FOREIGN KEY (transcript_id, recording_session_id)
        REFERENCES transcripts(id, recording_session_id) ON DELETE RESTRICT,
    CONSTRAINT fk_prepared_recording_workspace FOREIGN KEY (recording_session_id, workspace_id)
        REFERENCES recording_sessions(id, workspace_id) ON DELETE RESTRICT,
    CONSTRAINT fk_prepared_category_workspace FOREIGN KEY (existing_category_id, workspace_id)
        REFERENCES document_categories(id, workspace_id) ON DELETE RESTRICT,
    CONSTRAINT ck_prepared_decision CHECK ((
        (assignment_source IS NULL AND existing_category_id IS NULL AND new_category_name IS NULL
            AND new_category_description IS NULL)
        OR (assignment_source IN ('AI_REUSE', 'RULE_REUSE') AND existing_category_id IS NOT NULL
            AND new_category_name IS NULL AND new_category_description IS NULL)
        OR (assignment_source IN ('AI_NEW', 'RULE_NEW') AND existing_category_id IS NULL
            AND new_category_name IS NOT NULL AND new_category_description IS NOT NULL)
        OR (assignment_source = 'FALLBACK' AND existing_category_id IS NULL
            AND new_category_name IS NULL AND new_category_description IS NULL)
    ) IS TRUE)
);
