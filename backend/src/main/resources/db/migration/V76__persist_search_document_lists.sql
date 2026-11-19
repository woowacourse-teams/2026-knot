CREATE TABLE search_document_lists (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    conversation_id BIGINT NOT NULL REFERENCES search_conversations(id) ON DELETE CASCADE,
    first_answer_id BIGINT NOT NULL UNIQUE REFERENCES search_messages(id) ON DELETE CASCADE,
    cutoff_at TIMESTAMPTZ NOT NULL,
    recorded_from TIMESTAMPTZ,
    recorded_before TIMESTAMPTZ,
    query_text TEXT NOT NULL,
    sort_by_creation BOOLEAN NOT NULL,
    displayed_through INTEGER NOT NULL DEFAULT 0,
    item_count INTEGER NOT NULL DEFAULT 0,
    CONSTRAINT ck_search_document_list_position CHECK (displayed_through BETWEEN 0 AND item_count)
);

CREATE INDEX idx_search_document_lists_conversation
    ON search_document_lists (conversation_id, first_answer_id DESC);

CREATE TABLE search_document_list_items (
    list_id BIGINT NOT NULL REFERENCES search_document_lists(id) ON DELETE CASCADE,
    ordinal INTEGER NOT NULL CHECK (ordinal > 0),
    document_id BIGINT REFERENCES documents(id) ON DELETE SET NULL,
    PRIMARY KEY (list_id, ordinal),
    UNIQUE (list_id, document_id)
);

CREATE INDEX idx_search_document_list_items_document ON search_document_list_items (document_id);

CREATE TABLE search_document_list_answers (
    answer_id BIGINT PRIMARY KEY REFERENCES search_messages(id) ON DELETE CASCADE,
    list_id BIGINT NOT NULL REFERENCES search_document_lists(id) ON DELETE CASCADE,
    first_ordinal INTEGER NOT NULL CHECK (first_ordinal > 0),
    last_ordinal INTEGER NOT NULL CHECK (last_ordinal >= first_ordinal),
    selection BOOLEAN NOT NULL,
    CONSTRAINT ck_search_document_list_answer_selection CHECK (NOT selection OR first_ordinal = last_ordinal)
);

CREATE INDEX idx_search_document_list_answers_list ON search_document_list_answers (list_id);
