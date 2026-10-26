package com.knot.backend.document.domain.model;

import com.knot.backend.document.domain.DocumentErrorCode;
import com.knot.backend.document.domain.DocumentException;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.Table;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import lombok.Getter;

@Getter
@Entity
@Table(name = "document_generation_batches")
public class DocumentGenerationBatch {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "recording_session_id", nullable = false, updatable = false)
    private long recordingSessionId;

    @Column(name = "transcript_id")
    private Long transcriptId;

    // 이전 보존 정책이 입력을 해제한 기록이다. 현재 정책은 입력을 새로 해제하지 않는다.
    @Column(name = "released_transcript_id")
    private Long releasedTranscriptId;

    @Column(name = "input_released_at")
    private Instant inputReleasedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "topic_registration_state", nullable = false, length = 30)
    private DocumentTopicRegistrationState topicRegistrationState;

    @ElementCollection
    @CollectionTable(name = "document_generation_batch_topics", joinColumns = @JoinColumn(name = "batch_id"))
    @OrderColumn(name = "position")
    @Column(name = "topic", nullable = false, columnDefinition = "TEXT")
    private List<String> topics = new ArrayList<>();

    @Column(name = "accepted_at", nullable = false, updatable = false)
    private Instant acceptedAt;

    @Column(name = "registered_at")
    private Instant registeredAt;

    @Column(name = "cleanup_requested_at")
    private Instant cleanupRequestedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "processing_status", nullable = false, length = 20)
    private DocumentGenerationProcessingStatus processingStatus;

    @Column(name = "queued_count", nullable = false)
    private int queuedCount;

    @Column(name = "running_count", nullable = false)
    private int runningCount;

    @Column(name = "succeeded_count", nullable = false)
    private int succeededCount;

    @Column(name = "failed_count", nullable = false)
    private int failedCount;

    @Column(name = "finished_at")
    private Instant finishedAt;

    protected DocumentGenerationBatch() {}

    private DocumentGenerationBatch(
            long recordingSessionId,
            long transcriptId,
            Instant acceptedAt
    ) {
        validateIdentifier(recordingSessionId);
        validateIdentifier(transcriptId);
        validateTime(acceptedAt);
        this.recordingSessionId = recordingSessionId;
        this.transcriptId = transcriptId;
        this.acceptedAt = acceptedAt;
        this.topicRegistrationState = DocumentTopicRegistrationState.WAITING_CLASSIFICATION;
        this.processingStatus = DocumentGenerationProcessingStatus.QUEUED;
    }

    public static DocumentGenerationBatch accept(
            long recordingSessionId,
            long transcriptId,
            Instant acceptedAt
    ) {
        return new DocumentGenerationBatch(
                recordingSessionId,
                transcriptId,
                acceptedAt
        );
    }

    public List<String> getTopics() {
        return List.copyOf(topics);
    }

    public void validateInput(long inputTranscriptId) {
        if (releasedTranscriptId != null) {
            validateReleasedTranscript(inputTranscriptId);
            return;
        }
        if (transcriptId == null && topicRegistrationState != DocumentTopicRegistrationState.WAITING_CLASSIFICATION) {
            return;
        }
        if (transcriptId == null || transcriptId != inputTranscriptId) {
            throw new DocumentException(DocumentErrorCode.GENERATION_REGISTRATION_CONFLICT);
        }
    }

    public void validateGenerationTarget(
            DocumentGenerationJob job,
            long inputRecordingSessionId,
            long inputTranscriptId
    ) {
        if (topicRegistrationState != DocumentTopicRegistrationState.TOPICS_REGISTERED) {
            throw new DocumentException(DocumentErrorCode.GENERATION_REGISTRATION_CONFLICT);
        }
        if (transcriptId == null || transcriptId != inputTranscriptId) {
            throw new DocumentException(DocumentErrorCode.GENERATION_REGISTRATION_CONFLICT);
        }
        if (recordingSessionId != inputRecordingSessionId) {
            throw new DocumentException(DocumentErrorCode.GENERATION_REGISTRATION_CONFLICT);
        }
        if (!topics.contains(job.getTopic())) {
            throw new DocumentException(DocumentErrorCode.GENERATION_REGISTRATION_CONFLICT);
        }
    }

    public boolean isRegisteredWith(List<DocumentTopic> classifiedTopics) {
        if (topicRegistrationState == DocumentTopicRegistrationState.WAITING_CLASSIFICATION) {
            return false;
        }
        List<String> names = classifiedTopics.stream()
                .map(DocumentTopic::value)
                .toList();
        return topics.equals(names);
    }

    public void validateRegisteredWith(List<DocumentTopic> classifiedTopics) {
        if (!isRegisteredWith(classifiedTopics)) {
            throw new DocumentException(DocumentErrorCode.GENERATION_REGISTRATION_CONFLICT);
        }
    }

    public void registerTopics(
            List<DocumentTopic> classifiedTopics,
            Instant completedAt
    ) {
        validateWaiting();
        validateTime(completedAt);
        if (completedAt.isBefore(acceptedAt)) {
            throw new DocumentException(DocumentErrorCode.INVALID_DOCUMENT_DATA);
        }
        validateTopics(classifiedTopics);
        topics.addAll(
                classifiedTopics.stream()
                        .map(DocumentTopic::value)
                        .toList()
        );
        registeredAt = completedAt;
        if (classifiedTopics.isEmpty()) {
            topicRegistrationState = DocumentTopicRegistrationState.NO_CONTENT;
            cleanupRequestedAt = completedAt;
            processingStatus = DocumentGenerationProcessingStatus.NO_CONTENT;
            finishedAt = completedAt;
            return;
        }
        topicRegistrationState = DocumentTopicRegistrationState.TOPICS_REGISTERED;
        queuedCount = classifiedTopics.size();
        processingStatus = DocumentGenerationProcessingStatus.QUEUED;
        finishedAt = null;
    }

    public void recordJobTransition(
            DocumentGenerationJobStage stage,
            DocumentGenerationJobStatus previous,
            DocumentGenerationJobStatus next,
            Instant changedAt
    ) {
        validateTime(changedAt);
        if (previous == next) {
            return;
        }
        if (stage == DocumentGenerationJobStage.CLASSIFICATION) {
            recordClassificationTransition(
                    next,
                    changedAt
            );
            return;
        }
        if (topicRegistrationState != DocumentTopicRegistrationState.TOPICS_REGISTERED) {
            throw new DocumentException(DocumentErrorCode.GENERATION_REGISTRATION_CONFLICT);
        }
        if (count(previous) <= 0) {
            throw new DocumentException(DocumentErrorCode.GENERATION_REGISTRATION_CONFLICT);
        }
        changeCount(
                previous,
                -1
        );
        changeCount(
                next,
                1
        );
        refreshProcessingStatus();
        updateFinishedAt(changedAt);
    }

    private void recordClassificationTransition(
            DocumentGenerationJobStatus next,
            Instant changedAt
    ) {
        if (topicRegistrationState != DocumentTopicRegistrationState.WAITING_CLASSIFICATION) {
            return;
        }
        processingStatus = DocumentGenerationProcessingStatus.valueOf(next.name());
        updateFinishedAt(changedAt);
    }

    private int count(DocumentGenerationJobStatus status) {
        return switch (status) {
            case QUEUED -> queuedCount;
            case RUNNING -> runningCount;
            case SUCCEEDED -> succeededCount;
            case FAILED -> failedCount;
        };
    }

    private void changeCount(
            DocumentGenerationJobStatus status,
            int delta
    ) {
        switch (status) {
            case QUEUED -> queuedCount += delta;
            case RUNNING -> runningCount += delta;
            case SUCCEEDED -> succeededCount += delta;
            case FAILED -> failedCount += delta;
        }
    }

    private void refreshProcessingStatus() {
        if (runningCount > 0) {
            processingStatus = DocumentGenerationProcessingStatus.RUNNING;
            return;
        }
        if (queuedCount > 0) {
            processingStatus = DocumentGenerationProcessingStatus.QUEUED;
            return;
        }
        if (failedCount > 0) {
            processingStatus = DocumentGenerationProcessingStatus.FAILED;
            return;
        }
        processingStatus = DocumentGenerationProcessingStatus.SUCCEEDED;
    }

    private void updateFinishedAt(Instant changedAt) {
        if (processingStatus == DocumentGenerationProcessingStatus.QUEUED
                || processingStatus == DocumentGenerationProcessingStatus.RUNNING) {
            finishedAt = null;
            return;
        }
        finishedAt = changedAt;
    }

    private void validateReleasedTranscript(long inputTranscriptId) {
        if (releasedTranscriptId != inputTranscriptId) {
            throw new DocumentException(DocumentErrorCode.GENERATION_REGISTRATION_CONFLICT);
        }
    }

    private void validateWaiting() {
        if (topicRegistrationState != DocumentTopicRegistrationState.WAITING_CLASSIFICATION) {
            throw new DocumentException(DocumentErrorCode.GENERATION_REGISTRATION_CONFLICT);
        }
    }

    private void validateTopics(List<DocumentTopic> classifiedTopics) {
        if (classifiedTopics == null) {
            throw new DocumentException(DocumentErrorCode.INVALID_TOPIC_CLASSIFICATION_RESPONSE);
        }
        for (DocumentTopic topic : classifiedTopics) {
            if (topic == null) {
                throw new DocumentException(DocumentErrorCode.INVALID_TOPIC_CLASSIFICATION_RESPONSE);
            }
        }
        if (new HashSet<>(classifiedTopics).size() != classifiedTopics.size()) {
            throw new DocumentException(DocumentErrorCode.INVALID_TOPIC_CLASSIFICATION_RESPONSE);
        }
    }

    private void validateIdentifier(long identifier) {
        if (identifier <= 0) {
            throw new DocumentException(DocumentErrorCode.INVALID_DOCUMENT_DATA);
        }
    }

    private void validateTime(Instant time) {
        if (time == null) {
            throw new DocumentException(DocumentErrorCode.INVALID_DOCUMENT_DATA);
        }
        try {
            int year = time.atOffset(ZoneOffset.UTC)
                    .getYear();
            if (year < 1 || year > 9999) {
                throw new DocumentException(DocumentErrorCode.INVALID_DOCUMENT_DATA);
            }
        } catch (DateTimeException exception) {
            throw new DocumentException(
                    DocumentErrorCode.INVALID_DOCUMENT_DATA,
                    exception
            );
        }
    }
}
