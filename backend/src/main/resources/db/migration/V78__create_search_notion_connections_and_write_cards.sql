CREATE TABLE search_notion_connections (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    workspace_id BIGINT NOT NULL,
    connected_member_id BIGINT NOT NULL,
    notion_workspace_id VARCHAR(100) NOT NULL,
    notion_workspace_name VARCHAR(255),
    notion_workspace_icon TEXT,
    status VARCHAR(20) NOT NULL,
    encrypted_access_token TEXT NOT NULL,
    encrypted_refresh_token TEXT,
    access_token_expires_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT fk_search_notion_connections_workspace FOREIGN KEY (workspace_id) REFERENCES workspaces(id) ON DELETE RESTRICT,
    CONSTRAINT fk_search_notion_connections_member FOREIGN KEY (connected_member_id) REFERENCES members(id) ON DELETE RESTRICT,
    CONSTRAINT uk_search_notion_connections_workspace UNIQUE (workspace_id),
    CONSTRAINT ck_search_notion_connections_status CHECK (status IN ('CONNECTED', 'EXPIRED'))
);

CREATE INDEX idx_search_notion_connections_member ON search_notion_connections (connected_member_id);

CREATE TABLE search_notion_authorization_states (
    state_hash VARCHAR(64) PRIMARY KEY,
    workspace_id BIGINT NOT NULL,
    member_id BIGINT NOT NULL,
    encrypted_code_verifier TEXT NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_search_notion_authorization_states_workspace FOREIGN KEY (workspace_id) REFERENCES workspaces(id) ON DELETE CASCADE,
    CONSTRAINT fk_search_notion_authorization_states_member FOREIGN KEY (member_id) REFERENCES members(id) ON DELETE CASCADE
);

CREATE INDEX idx_search_notion_authorization_states_expires ON search_notion_authorization_states (expires_at);

CREATE TABLE search_write_cards (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    answer_message_id BIGINT NOT NULL,
    conversation_id BIGINT NOT NULL,
    workspace_id BIGINT NOT NULL,
    member_id BIGINT NOT NULL,
    type VARCHAR(40) NOT NULL,
    status VARCHAR(20) NOT NULL,
    notion_workspace_id VARCHAR(100) NOT NULL,
    location_notion_id VARCHAR(200) NOT NULL,
    location_kind VARCHAR(20) NOT NULL,
    location_name TEXT NOT NULL,
    location_path TEXT NOT NULL,
    title TEXT NOT NULL,
    body TEXT NOT NULL,
    source_document_id BIGINT NOT NULL,
    source_document_title TEXT NOT NULL,
    meeting_date DATE,
    executable BOOLEAN NOT NULL,
    created_page_url TEXT,
    retry_reason VARCHAR(40),
    failure_reason VARCHAR(40),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT fk_search_write_cards_answer FOREIGN KEY (answer_message_id) REFERENCES search_messages(id) ON DELETE RESTRICT,
    CONSTRAINT fk_search_write_cards_conversation FOREIGN KEY (conversation_id) REFERENCES search_conversations(id) ON DELETE RESTRICT,
    CONSTRAINT fk_search_write_cards_workspace FOREIGN KEY (workspace_id) REFERENCES workspaces(id) ON DELETE RESTRICT,
    CONSTRAINT fk_search_write_cards_member FOREIGN KEY (member_id) REFERENCES members(id) ON DELETE RESTRICT,
    CONSTRAINT fk_search_write_cards_document FOREIGN KEY (source_document_id) REFERENCES documents(id) ON DELETE RESTRICT,
    CONSTRAINT uk_search_write_cards_answer UNIQUE (answer_message_id),
    CONSTRAINT ck_search_write_cards_type CHECK (type IN ('NOTION_PAGE_CREATE')),
    CONSTRAINT ck_search_write_cards_status CHECK (status IN ('PENDING', 'RUNNING', 'DONE', 'CANCELED', 'FAILED')),
    CONSTRAINT ck_search_write_cards_location_kind CHECK (location_kind IN ('PAGE', 'DATABASE')),
    CONSTRAINT ck_search_write_cards_done_url CHECK ((status = 'DONE') = (created_page_url IS NOT NULL)),
    CONSTRAINT ck_search_write_cards_failure CHECK ((status = 'FAILED') = (failure_reason IS NOT NULL)),
    CONSTRAINT ck_search_write_cards_retry CHECK (retry_reason IS NULL OR status = 'PENDING')
);

CREATE INDEX idx_search_write_cards_conversation_status ON search_write_cards (conversation_id, status);

CREATE TABLE search_write_card_database_fields (
    write_card_id BIGINT NOT NULL,
    position INTEGER NOT NULL,
    name TEXT NOT NULL,
    value TEXT NOT NULL,
    property_type VARCHAR(40) NOT NULL,
    CONSTRAINT pk_search_write_card_database_fields PRIMARY KEY (write_card_id, position),
    CONSTRAINT fk_search_write_card_database_fields_card FOREIGN KEY (write_card_id) REFERENCES search_write_cards(id) ON DELETE CASCADE,
    CONSTRAINT ck_search_write_card_database_fields_position CHECK (position >= 0)
);

CREATE TABLE search_created_notion_pages (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    write_card_id BIGINT NOT NULL,
    workspace_id BIGINT NOT NULL,
    conversation_id BIGINT NOT NULL,
    source_document_id BIGINT NOT NULL,
    notion_workspace_id VARCHAR(100) NOT NULL,
    notion_page_id VARCHAR(200) NOT NULL,
    url TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_search_created_notion_pages_card FOREIGN KEY (write_card_id) REFERENCES search_write_cards(id) ON DELETE RESTRICT,
    CONSTRAINT fk_search_created_notion_pages_conversation FOREIGN KEY (conversation_id) REFERENCES search_conversations(id) ON DELETE RESTRICT,
    CONSTRAINT uk_search_created_notion_pages_card UNIQUE (write_card_id)
);

CREATE INDEX idx_search_created_notion_pages_history
    ON search_created_notion_pages (conversation_id, source_document_id, created_at DESC, id DESC);

CREATE TABLE search_notion_evidences (
    message_id BIGINT NOT NULL,
    rank INTEGER NOT NULL,
    title TEXT NOT NULL,
    url TEXT NOT NULL,
    CONSTRAINT pk_search_notion_evidences PRIMARY KEY (message_id, rank),
    CONSTRAINT fk_search_notion_evidences_message FOREIGN KEY (message_id) REFERENCES search_messages(id) ON DELETE RESTRICT,
    CONSTRAINT ck_search_notion_evidences_rank CHECK (rank > 0)
);

CREATE TABLE search_notion_notices (
    message_id BIGINT PRIMARY KEY,
    reason VARCHAR(40) NOT NULL,
    CONSTRAINT fk_search_notion_notices_message FOREIGN KEY (message_id) REFERENCES search_messages(id) ON DELETE RESTRICT,
    CONSTRAINT ck_search_notion_notices_reason CHECK (reason IN ('NOT_CONNECTED', 'PAGE_NOT_FOUND', 'READ_FAILED', 'CONNECTION_EXPIRED'))
);
