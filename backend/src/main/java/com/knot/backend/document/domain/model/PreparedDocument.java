package com.knot.backend.document.domain.model;

import com.knot.backend.document.domain.DocumentErrorCode;
import com.knot.backend.document.domain.DocumentException;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;

@Getter
@Entity
@Table(name = "prepared_documents")
public class PreparedDocument {

    @Id
    @Column(name = "job_id", updatable = false)
    private Long jobId;

    @Column(name = "workspace_id", nullable = false, updatable = false)
    private long workspaceId;

    @Column(name = "transcript_id", nullable = false, updatable = false)
    private long transcriptId;

    @Column(name = "recording_session_id", nullable = false, updatable = false)
    private long recordingSessionId;

    @Column(nullable = false, updatable = false, columnDefinition = "TEXT")
    private String title;

    @Column(updatable = false, columnDefinition = "TEXT")
    private String summary;

    @Column(nullable = false, updatable = false, columnDefinition = "TEXT")
    private String content;

    @Embedded
    private DocumentCategoryDecision decision;

    @Column(name = "catalog_version", length = 64)
    private String catalogVersion;

    @Column(name = "rule_version", length = 40)
    private String ruleVersion;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected PreparedDocument() {}

    public PreparedDocument(
            long jobId,
            long workspaceId,
            long transcriptId,
            long recordingSessionId,
            String title,
            String summary,
            String content,
            Instant createdAt
    ) {
        if (jobId <= 0 || workspaceId <= 0 || transcriptId <= 0 || recordingSessionId <= 0) {
            throw new DocumentException(DocumentErrorCode.INVALID_DOCUMENT_DATA);
        }
        if (DocumentText.isBlank(title) || DocumentText.isBlank(content) || createdAt == null) {
            throw new DocumentException(DocumentErrorCode.INVALID_DOCUMENT_GENERATION_RESPONSE);
        }
        this.jobId = jobId;
        this.workspaceId = workspaceId;
        this.transcriptId = transcriptId;
        this.recordingSessionId = recordingSessionId;
        this.title = title;
        this.summary = summary;
        this.content = content;
        this.createdAt = createdAt;
    }

    public void assign(
            DocumentCategoryDecision decision,
            String catalogVersion,
            String ruleVersion
    ) {
        if (decision == null || DocumentText.isBlank(catalogVersion) || DocumentText.isBlank(ruleVersion)) {
            throw new DocumentException(DocumentErrorCode.INVALID_DOCUMENT_DATA);
        }
        this.decision = decision;
        this.catalogVersion = catalogVersion;
        this.ruleVersion = ruleVersion;
    }
}
