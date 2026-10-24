ALTER TABLE documents
    ADD CONSTRAINT uk_documents_workspace UNIQUE (id, workspace_id);

CREATE TABLE document_topic_operations (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    operation_id UUID NOT NULL UNIQUE,
    workspace_id BIGINT NOT NULL REFERENCES workspaces(id) ON DELETE RESTRICT,
    requester_id BIGINT NOT NULL,
    request_id UUID NOT NULL,
    kind VARCHAR(20) NOT NULL CHECK (kind IN ('REORGANIZE', 'RESTORE')),
    status VARCHAR(30) NOT NULL CHECK (
        status IN ('QUEUED', 'RUNNING', 'CANCEL_REQUESTED', 'APPLYING', 'SUCCEEDED', 'FAILED', 'CANCELLED')
    ),
    created_at TIMESTAMPTZ NOT NULL,
    deadline_at TIMESTAMPTZ NOT NULL,
    next_attempt_at TIMESTAMPTZ NOT NULL,
    claim_token UUID,
    lease_until TIMESTAMPTZ,
    finished_at TIMESTAMPTZ,
    failure_code VARCHAR(80),
    retry_count INTEGER NOT NULL DEFAULT 0 CHECK (retry_count BETWEEN 0 AND 1),
    target_count INTEGER NOT NULL DEFAULT 0 CHECK (target_count >= 0),
    processed_count INTEGER NOT NULL DEFAULT 0 CHECK (processed_count BETWEEN 0 AND target_count),
    changed_count INTEGER NOT NULL DEFAULT 0 CHECK (changed_count BETWEEN 0 AND target_count),
    consolidated BOOLEAN NOT NULL DEFAULT FALSE,
    UNIQUE (workspace_id, request_id),
    UNIQUE (id, workspace_id),
    CHECK (deadline_at = created_at + INTERVAL '30 minutes'),
    CHECK (
        (status IN ('RUNNING', 'APPLYING') AND claim_token IS NOT NULL AND lease_until IS NOT NULL)
        OR (status NOT IN ('RUNNING', 'APPLYING') AND claim_token IS NULL AND lease_until IS NULL)
    ),
    CHECK ((status IN ('SUCCEEDED', 'FAILED', 'CANCELLED')) = (finished_at IS NOT NULL))
);

CREATE UNIQUE INDEX uq_document_topic_operation_active
    ON document_topic_operations(workspace_id)
    WHERE status IN ('QUEUED', 'RUNNING', 'CANCEL_REQUESTED', 'APPLYING');

CREATE INDEX idx_document_topic_operation_dispatch
    ON document_topic_operations(next_attempt_at, id)
    WHERE status IN ('QUEUED', 'RUNNING');

CREATE TABLE document_topic_operation_categories (
    operation_id BIGINT NOT NULL,
    workspace_id BIGINT NOT NULL,
    category_key VARCHAR(80) NOT NULL,
    existing_category_id BIGINT,
    name VARCHAR(80) NOT NULL,
    description VARCHAR(500) NOT NULL,
    was_candidate BOOLEAN NOT NULL DEFAULT FALSE,
    selected BOOLEAN NOT NULL DEFAULT FALSE,
    PRIMARY KEY (operation_id, category_key),
    FOREIGN KEY (operation_id, workspace_id)
        REFERENCES document_topic_operations(id, workspace_id) ON DELETE CASCADE,
    FOREIGN KEY (existing_category_id, workspace_id)
        REFERENCES document_categories(id, workspace_id) ON DELETE RESTRICT
);

CREATE TABLE document_topic_operation_targets (
    operation_id BIGINT NOT NULL,
    workspace_id BIGINT NOT NULL,
    document_id BIGINT NOT NULL,
    before_category_id BIGINT,
    before_grouping_scheme VARCHAR(20) NOT NULL,
    before_assignment_source VARCHAR(20),
    before_rule_version VARCHAR(40),
    after_key VARCHAR(80),
    changed BOOLEAN NOT NULL DEFAULT FALSE,
    PRIMARY KEY (operation_id, document_id),
    FOREIGN KEY (document_id, workspace_id)
        REFERENCES documents(id, workspace_id) ON DELETE RESTRICT,
    FOREIGN KEY (operation_id, workspace_id)
        REFERENCES document_topic_operations(id, workspace_id) ON DELETE CASCADE,
    FOREIGN KEY (before_category_id, workspace_id)
        REFERENCES document_categories(id, workspace_id) ON DELETE RESTRICT,
    FOREIGN KEY (operation_id, after_key)
        REFERENCES document_topic_operation_categories(operation_id, category_key) ON DELETE RESTRICT
);

CREATE TABLE document_topic_recoveries (
    workspace_id BIGINT PRIMARY KEY REFERENCES workspaces(id) ON DELETE RESTRICT,
    version_id UUID NOT NULL UNIQUE,
    source_operation_id BIGINT NOT NULL UNIQUE,
    created_at TIMESTAMPTZ NOT NULL,
    FOREIGN KEY (source_operation_id, workspace_id)
        REFERENCES document_topic_operations(id, workspace_id) ON DELETE RESTRICT
);

ALTER TABLE document_categories
    ADD COLUMN active_for_assignment BOOLEAN NOT NULL DEFAULT TRUE;

ALTER TABLE documents
    DROP CONSTRAINT ck_document_grouping,
    ADD CONSTRAINT ck_document_grouping CHECK ((
        (grouping_scheme = 'LEGACY' AND category_id IS NULL AND assignment_source IS NULL)
        OR (grouping_scheme = 'BROAD' AND category_id IS NOT NULL
            AND assignment_source IN ('AI_REUSE', 'AI_NEW', 'RULE_REUSE', 'RULE_NEW', 'FALLBACK', 'REORGANIZATION')
            AND assignment_rule_version IS NOT NULL)
    ) IS TRUE);
