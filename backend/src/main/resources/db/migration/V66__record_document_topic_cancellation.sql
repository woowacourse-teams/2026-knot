ALTER TABLE document_topic_operations
    ADD COLUMN cancel_requested_at TIMESTAMPTZ,
    ADD CONSTRAINT ck_document_topic_cancellation_time CHECK (
        (status IN ('CANCEL_REQUESTED', 'CANCELLED')) = (cancel_requested_at IS NOT NULL)
    );

DROP INDEX idx_document_topic_operation_dispatch;

CREATE INDEX idx_document_topic_operation_dispatch
    ON document_topic_operations(next_attempt_at, id)
    WHERE status IN ('QUEUED', 'RUNNING', 'APPLYING');

CREATE INDEX idx_document_topic_operation_cancellation
    ON document_topic_operations(cancel_requested_at, id)
    WHERE status = 'CANCEL_REQUESTED';
