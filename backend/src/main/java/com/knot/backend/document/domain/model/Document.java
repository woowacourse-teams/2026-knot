package com.knot.backend.document.domain.model;

import com.knot.backend.document.domain.DocumentErrorCode;
import com.knot.backend.document.domain.DocumentException;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;

@Getter
@Entity
@Table(name = "documents")
public class Document {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "workspace_id", nullable = false, updatable = false)
    private long workspaceId;

    @Column(name = "recording_session_id", nullable = false, updatable = false)
    private long recordingSessionId;

    @Column(name = "source_transcript_id", nullable = false, updatable = false)
    private long sourceTranscriptId;

    @Column(name = "document_generation_job_id", nullable = false, updatable = false)
    private long documentGenerationJobId;

    @Column(nullable = false, updatable = false, columnDefinition = "text")
    private String topic;

    @Column(nullable = false, updatable = false, columnDefinition = "text")
    private String title;

    @Column(updatable = false, columnDefinition = "text")
    private String summary;

    @Column(nullable = false, updatable = false, columnDefinition = "text")
    private String content;

    @Column(name = "category_id", updatable = false)
    private Long categoryId;

    @Enumerated(EnumType.STRING)
    @Column(name = "grouping_scheme", nullable = false, updatable = false, length = 20)
    private DocumentGroupingScheme groupingScheme = DocumentGroupingScheme.LEGACY;

    @Enumerated(EnumType.STRING)
    @Column(name = "assignment_source", updatable = false, length = 20)
    private DocumentCategoryAssignmentSource assignmentSource;

    @Column(name = "assignment_rule_version", updatable = false, length = 40)
    private String assignmentRuleVersion;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private DocumentStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "archived_at")
    private Instant archivedAt;

    protected Document() {}

    public void assignCategory(
            DocumentCategory category,
            DocumentCategoryAssignmentSource source,
            String ruleVersion
    ) {
        if (id != null || category == null || source == null || DocumentText.isBlank(ruleVersion)) {
            throw new DocumentException(DocumentErrorCode.INVALID_DOCUMENT_DATA);
        }
        if (category.getId() == null || category.getWorkspaceId() != workspaceId) {
            throw new DocumentException(DocumentErrorCode.INVALID_DOCUMENT_DATA);
        }
        categoryId = category.getId();
        groupingScheme = DocumentGroupingScheme.BROAD;
        assignmentSource = source;
        assignmentRuleVersion = ruleVersion;
    }

    public void validateGeneratedBy(
            DocumentGenerationJob job,
            long workspaceId
    ) {
        if (this.workspaceId != workspaceId) {
            throw new DocumentException(DocumentErrorCode.GENERATION_REGISTRATION_CONFLICT);
        }
        if (sourceTranscriptId != job.getTranscriptId()) {
            throw new DocumentException(DocumentErrorCode.GENERATION_REGISTRATION_CONFLICT);
        }
        if (!topic.equals(job.getTopic())) {
            throw new DocumentException(DocumentErrorCode.GENERATION_REGISTRATION_CONFLICT);
        }
    }

    public void archive(Instant archivedAt) {
        if (status == DocumentStatus.ARCHIVED) {
            return;
        }
        validateArchiveTime(archivedAt);
        this.status = DocumentStatus.ARCHIVED;
        this.archivedAt = archivedAt;
    }

    private void validateArchiveTime(Instant archivedAt) {
        if (archivedAt == null) {
            throw new DocumentException(DocumentErrorCode.INVALID_DOCUMENT_DATA);
        }
        if (archivedAt.isBefore(createdAt)) {
            throw new DocumentException(DocumentErrorCode.INVALID_DOCUMENT_DATA);
        }
    }

    private Document(
            long workspaceId,
            long recordingSessionId,
            long sourceTranscriptId,
            long documentGenerationJobId,
            String topic,
            String title,
            String summary,
            String content,
            Instant createdAt
    ) {
        validateSourceId(workspaceId);
        validateSourceId(recordingSessionId);
        validateSourceId(sourceTranscriptId);
        validateSourceId(documentGenerationJobId);
        validateRequiredText(topic);
        validateRequiredText(title);
        validateRequiredText(content);
        validateCreationTime(createdAt);
        this.workspaceId = workspaceId;
        this.recordingSessionId = recordingSessionId;
        this.sourceTranscriptId = sourceTranscriptId;
        this.documentGenerationJobId = documentGenerationJobId;
        this.topic = topic;
        this.title = title;
        this.summary = summary;
        this.content = content;
        this.status = DocumentStatus.DRAFT;
        this.createdAt = createdAt;
    }

    private void validateSourceId(long sourceId) {
        if (sourceId <= 0) {
            throw new DocumentException(DocumentErrorCode.INVALID_DOCUMENT_DATA);
        }
    }

    private void validateRequiredText(String value) {
        if (DocumentText.isBlank(value)) {
            throw new DocumentException(DocumentErrorCode.INVALID_DOCUMENT_DATA);
        }
    }

    private void validateCreationTime(Instant createdAt) {
        if (createdAt == null) {
            throw new DocumentException(DocumentErrorCode.INVALID_DOCUMENT_DATA);
        }
    }

    public static Document createDraft(
            long workspaceId,
            long recordingSessionId,
            long sourceTranscriptId,
            long documentGenerationJobId,
            String topic,
            String title,
            String summary,
            String content,
            Instant createdAt
    ) {
        return new Document(
                workspaceId,
                recordingSessionId,
                sourceTranscriptId,
                documentGenerationJobId,
                topic,
                title,
                summary,
                content,
                createdAt
        );
    }

}
