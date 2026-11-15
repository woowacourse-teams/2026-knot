CREATE TABLE search_question_contexts (
    question_message_id BIGINT PRIMARY KEY REFERENCES search_messages(id) ON DELETE CASCADE,
    search_text TEXT NOT NULL,
    recorded_from TIMESTAMPTZ,
    recorded_before TIMESTAMPTZ,
    recording_session_id BIGINT,
    CONSTRAINT ck_search_question_context_period CHECK (
        (recorded_from IS NULL AND recorded_before IS NULL)
        OR (recorded_from IS NOT NULL AND recorded_before IS NOT NULL AND recorded_from < recorded_before)
    ),
    CONSTRAINT ck_search_question_context_recording CHECK (recording_session_id > 0)
);
