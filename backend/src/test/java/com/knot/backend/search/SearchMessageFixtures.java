package com.knot.backend.search;

import com.knot.backend.document.DocumentFixtures;
import com.knot.backend.search.domain.model.SearchEvidenceVersion;
import org.springframework.jdbc.core.simple.JdbcClient;

public class SearchMessageFixtures {

    private final JdbcClient jdbc;
    private final DocumentFixtures documents;

    public SearchMessageFixtures(JdbcClient jdbc) {
        this.jdbc = jdbc;
        this.documents = new DocumentFixtures(jdbc);
    }

    public long messageId(
            long conversationId,
            int sequence
    ) {
        return jdbc.sql("SELECT id FROM search_messages WHERE conversation_id = :id AND sequence = :sequence")
                .param(
                        "id",
                        conversationId
                )
                .param(
                        "sequence",
                        sequence
                )
                .query(Long.class)
                .single();
    }

    public long saveDocument(
            long workspaceId,
            long memberId
    ) {
        long recordingId = documents.saveRecording(
                workspaceId,
                memberId,
                60000
        );
        long transcriptId = documents.saveTranscript(recordingId);
        long jobId = documents.saveJob(
                transcriptId,
                "SUCCEEDED"
        );
        return documents.saveDocument(
                workspaceId,
                recordingId,
                transcriptId,
                jobId,
                "탐색 테스트"
        );
    }

    public String sourceVersion(long documentId) {
        return jdbc.sql("SELECT title, topic, content FROM documents WHERE id = :id")
                .param(
                        "id",
                        documentId
                )
                .query(
                        (
                                row,
                                index
                        ) -> SearchEvidenceVersion.fromDocument(
                                row.getString("title"),
                                row.getString("topic"),
                                row.getString("content")
                        )
                )
                .single();
    }

    public void saveEvidence(
            long messageId,
            long documentId,
            int rank
    ) {
        saveEvidence(
                messageId,
                documentId,
                rank,
                null
        );
    }

    public void saveEvidence(
            long messageId,
            long documentId,
            int rank,
            String sourceVersion
    ) {
        jdbc.sql("""
                INSERT INTO search_evidences (message_id, document_id, live_document_id, rank, source_version)
                VALUES (:message, :document, :document, :rank, :version)
                """)
                .param(
                        "message",
                        messageId
                )
                .param(
                        "document",
                        documentId
                )
                .param(
                        "rank",
                        rank
                )
                .param(
                        "version",
                        sourceVersion
                )
                .update();
    }
}
