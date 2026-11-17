ALTER TABLE search_question_contexts
    DROP CONSTRAINT ck_search_question_context_period,
    ADD CONSTRAINT ck_search_question_context_period CHECK (
        recorded_from IS NULL OR recorded_before IS NULL OR recorded_from < recorded_before
    );
