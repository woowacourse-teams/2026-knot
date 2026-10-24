ALTER TABLE document_topic_operations
    ADD COLUMN target_version_id BIGINT,
    ADD COLUMN restoration_source_operation_id BIGINT,
    ADD CONSTRAINT fk_topic_restoration_source FOREIGN KEY (restoration_source_operation_id, workspace_id)
        REFERENCES document_topic_operations(id, workspace_id) ON DELETE RESTRICT,
    ADD CONSTRAINT ck_topic_restoration_metadata CHECK ((
        (kind = 'REORGANIZE' AND target_version_id IS NULL AND restoration_source_operation_id IS NULL)
        OR (kind = 'RESTORE' AND target_version_id > 0 AND restoration_source_operation_id > 0
            AND restoration_source_operation_id <> id)
    ) IS TRUE);

CREATE TABLE document_topic_restoration_targets (
    operation_id BIGINT NOT NULL,
    workspace_id BIGINT NOT NULL,
    document_id BIGINT NOT NULL,
    expected_category_id BIGINT,
    expected_grouping_scheme VARCHAR(20) NOT NULL,
    expected_assignment_source VARCHAR(20),
    expected_rule_version VARCHAR(40),
    restored_category_id BIGINT,
    restored_grouping_scheme VARCHAR(20) NOT NULL,
    restored_assignment_source VARCHAR(20),
    restored_rule_version VARCHAR(40),
    prepared BOOLEAN NOT NULL DEFAULT FALSE,
    PRIMARY KEY (operation_id, document_id),
    FOREIGN KEY (operation_id, workspace_id)
        REFERENCES document_topic_operations(id, workspace_id) ON DELETE CASCADE,
    FOREIGN KEY (document_id, workspace_id)
        REFERENCES documents(id, workspace_id) ON DELETE RESTRICT,
    FOREIGN KEY (expected_category_id, workspace_id)
        REFERENCES document_categories(id, workspace_id) ON DELETE RESTRICT,
    FOREIGN KEY (restored_category_id, workspace_id)
        REFERENCES document_categories(id, workspace_id) ON DELETE RESTRICT,
    CONSTRAINT ck_restoration_expected_assignment CHECK ((
        (expected_grouping_scheme = 'LEGACY' AND expected_category_id IS NULL AND expected_assignment_source IS NULL)
        OR (expected_grouping_scheme = 'BROAD' AND expected_category_id IS NOT NULL
            AND expected_assignment_source IN ('AI_REUSE','AI_NEW','RULE_REUSE','RULE_NEW','FALLBACK','REORGANIZATION')
            AND expected_rule_version IS NOT NULL)
    ) IS TRUE),
    CONSTRAINT ck_restoration_restored_assignment CHECK ((
        (restored_grouping_scheme = 'LEGACY' AND restored_category_id IS NULL AND restored_assignment_source IS NULL)
        OR (restored_grouping_scheme = 'BROAD' AND restored_category_id IS NOT NULL
            AND restored_assignment_source IN ('AI_REUSE','AI_NEW','RULE_REUSE','RULE_NEW','FALLBACK','REORGANIZATION')
            AND restored_rule_version IS NOT NULL)
    ) IS TRUE)
);

CREATE INDEX idx_topic_restoration_pending ON document_topic_restoration_targets(operation_id, document_id)
    WHERE NOT prepared;
