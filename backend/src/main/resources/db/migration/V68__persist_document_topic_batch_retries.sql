ALTER TABLE document_topic_operation_targets
    ADD COLUMN retry_count INTEGER NOT NULL DEFAULT 0 CHECK (retry_count BETWEEN 0 AND 1),
    ADD COLUMN next_attempt_at TIMESTAMPTZ;

CREATE INDEX idx_document_topic_target_pending
    ON document_topic_operation_targets(operation_id, document_id)
    WHERE after_key IS NULL;
