package com.knot.backend.document.domain.model;
public enum DocumentTopicOperationStatus {
    QUEUED,
    RUNNING,
    CANCEL_REQUESTED,
    APPLYING,
    SUCCEEDED,
    FAILED,
    CANCELLED;

    public DocumentTopicOperationStatus reportedStatus(int retryCount) {
        if (this == QUEUED && retryCount > 0) {
            return RUNNING;
        }
        return this;
    }
}
