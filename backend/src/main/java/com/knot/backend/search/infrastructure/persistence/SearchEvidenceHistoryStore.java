package com.knot.backend.search.infrastructure.persistence;

import com.knot.backend.search.application.SearchAnswerContentPolicy.EvidenceStatus;
import com.knot.backend.search.domain.model.SearchEvidenceVersion;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
@RequiredArgsConstructor
class SearchEvidenceHistoryStore {
    private final JdbcClient jdbc;

    @Transactional(readOnly = true, timeout = 2)
    public List<Access> access(
            long workspaceId,
            long memberId,
            long conversationId,
            List<Long> answerIds
    ) {
        return jdbc
                .sql(
                        """
                                SELECT m.id, h.answer_id IS NOT NULL AS hidden,
                                    m.status = 'COMPLETED' AND EXISTS (SELECT 1 FROM search_evidences e WHERE e.message_id = m.id) AS has_sources
                                FROM search_messages m
                                JOIN search_conversations c ON c.id = m.conversation_id
                                JOIN workspace_members w ON w.workspace_id = c.workspace_id AND w.member_id = c.member_id
                                LEFT JOIN search_hidden_answer_bodies h ON h.answer_id = m.id
                                WHERE c.workspace_id = :workspace AND c.member_id = :member AND c.id = :conversation
                                    AND m.role = 'ASSISTANT' AND m.id IN (:answers)
                                """
                )
                .param(
                        "workspace",
                        workspaceId
                )
                .param(
                        "member",
                        memberId
                )
                .param(
                        "conversation",
                        conversationId
                )
                .param(
                        "answers",
                        answerIds
                )
                .query(
                        (
                                row,
                                index
                        ) -> new Access(
                                row.getLong("id"),
                                row.getBoolean("hidden"),
                                row.getBoolean("has_sources")
                        )
                )
                .list();
    }

    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW, timeout = 2)
    public List<Access> recheckAccess(
            long workspaceId,
            long memberId,
            long conversationId,
            List<Long> answerIds
    ) {
        return access(
                workspaceId,
                memberId,
                conversationId,
                answerIds
        );
    }

    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW, timeout = 2)
    public Map<Long, EvidenceStatus> compare(
            long workspaceId,
            long memberId,
            long conversationId,
            List<Long> answerIds
    ) {
        return jdbc.sql("""
                SELECT m.id, e.source_version, d.id AS current_id, d.title, d.topic, d.content
                FROM search_evidence_access e
                JOIN search_messages m ON m.id = e.message_id
                JOIN search_conversations c ON c.id = m.conversation_id
                JOIN workspace_members w ON w.workspace_id = c.workspace_id AND w.member_id = c.member_id
                LEFT JOIN documents d ON d.id = e.readable_document_id AND e.source_version IS NOT NULL
                WHERE c.workspace_id = :workspace AND c.member_id = :member AND c.id = :conversation
                    AND m.id IN (:answers)
                """)
                .param(
                        "workspace",
                        workspaceId
                )
                .param(
                        "member",
                        memberId
                )
                .param(
                        "conversation",
                        conversationId
                )
                .param(
                        "answers",
                        answerIds
                )
                .query(rows -> {
                    Map<Long, EvidenceStatus> results = new HashMap<>();
                    while (rows.next()) {
                        String version = rows.getString("source_version");
                        EvidenceStatus status = EvidenceStatus.CHANGE_UNKNOWN;
                        if (version != null && rows.getObject("current_id") != null) {
                            String current = SearchEvidenceVersion.fromDocument(
                                    rows.getString("title"),
                                    rows.getString("topic"),
                                    rows.getString("content")
                            );
                            status = version.equals(current) ? EvidenceStatus.AVAILABLE : EvidenceStatus.SOURCE_CHANGED;
                        }
                        results.merge(
                                rows.getLong("id"),
                                status,
                                this::combine
                        );
                    }
                    return results;
                });
    }

    private EvidenceStatus combine(
            EvidenceStatus left,
            EvidenceStatus right
    ) {
        if (left == EvidenceStatus.SOURCE_CHANGED || right == EvidenceStatus.SOURCE_CHANGED) {
            return EvidenceStatus.SOURCE_CHANGED;
        }
        if (left == EvidenceStatus.CHANGE_UNKNOWN || right == EvidenceStatus.CHANGE_UNKNOWN) {
            return EvidenceStatus.CHANGE_UNKNOWN;
        }
        return EvidenceStatus.AVAILABLE;
    }

    record Access(
            long answerId,
            boolean hidden,
            boolean hasSources
    ) {
    }
}
