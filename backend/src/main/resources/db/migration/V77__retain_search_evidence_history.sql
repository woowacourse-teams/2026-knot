ALTER TABLE search_evidences
    ADD COLUMN live_document_id BIGINT,
    ADD COLUMN source_version VARCHAR(43);

UPDATE search_evidences SET live_document_id = document_id;

ALTER TABLE search_evidences
    DROP CONSTRAINT fk_search_evidences_document,
    ADD CONSTRAINT fk_search_evidences_live_document
        FOREIGN KEY (live_document_id) REFERENCES documents(id) ON DELETE SET NULL,
    ADD CONSTRAINT ck_search_evidences_live_document
        CHECK (live_document_id IS NULL OR live_document_id = document_id);

CREATE INDEX idx_search_evidences_live_document ON search_evidences (live_document_id);

ALTER TABLE search_messages
    ADD COLUMN answer_kind VARCHAR(30),
    ADD CONSTRAINT ck_search_messages_answer_kind CHECK (
        answer_kind IS NULL OR (role = 'ASSISTANT' AND status = 'COMPLETED'
            AND answer_kind IN ('DOCUMENT_ANSWER', 'SERVER_GUIDANCE', 'UNVERIFIED_ANSWER'))
    );

UPDATE search_messages m
SET answer_kind = 'DOCUMENT_ANSWER'
WHERE m.role = 'ASSISTANT' AND m.status = 'COMPLETED'
    AND EXISTS (SELECT 1 FROM search_evidences e WHERE e.message_id = m.id);

UPDATE search_messages
SET answer_kind = 'SERVER_GUIDANCE'
WHERE role = 'ASSISTANT' AND status = 'COMPLETED' AND answer_kind IS NULL
    AND content IN (
        '안녕하세요. 회의 기록에서 궁금한 내용을 질문해 주세요.',
        'Knot 탐색에서는 모아 둔 회의 문서를 조회·요약·비교할 수 있어요. 찾고 싶은 내용을 질문해 주세요.',
        '어떤 회의나 안건을 찾고 싶은지 조금 더 구체적으로 질문해 주세요.',
        'Knot은 회의 기록의 조회·요약·비교를 도와드려요. 찾고 싶은 회의 내용을 질문해 주세요.',
        '현재 검색 가능한 회의 문서에서는 해당 내용을 확인하지 못했습니다. 내용을 녹음하고 문서 준비가 끝난 뒤 다시 질문해 주세요.',
        '어느 날짜나 기간에 열린 회의인지 알려주세요.',
        '어느 회의를 말씀하시는지 회의 제목이나 날짜를 알려주세요.',
        '질문은 대화에 남겨 두었어요. 지금은 답변을 정리하기 어려워요. 잠시 뒤 다시 질문해 주시면 이어서 도와드릴게요.'
    );

CREATE VIEW search_evidence_access AS
SELECT e.message_id, e.document_id, e.rank, e.source_version, d.id AS readable_document_id
FROM search_evidences e
JOIN search_messages m ON m.id = e.message_id
JOIN search_conversations c ON c.id = m.conversation_id
LEFT JOIN documents d ON d.id = e.live_document_id AND d.workspace_id = c.workspace_id
    AND d.status IN ('DRAFT', 'ARCHIVED');

CREATE VIEW search_hidden_answer_bodies AS
SELECT m.id AS answer_id
FROM search_messages m
WHERE m.role = 'ASSISTANT' AND m.status = 'COMPLETED'
    AND (EXISTS (
        SELECT 1 FROM search_evidence_access e WHERE e.message_id = m.id AND e.readable_document_id IS NULL
    ) OR (
        (m.answer_kind IS NULL OR m.answer_kind = 'DOCUMENT_ANSWER')
        AND NOT EXISTS (SELECT 1 FROM search_evidences e WHERE e.message_id = m.id)
        AND NOT EXISTS (SELECT 1 FROM search_document_list_answers l WHERE l.answer_id = m.id)
    ));
