package com.knot.backend.document.domain.model;
public enum DocumentTopicOperationStatus {
    QUEUED,
    RUNNING,
    CANCEL_REQUESTED,
    APPLYING,
    SUCCEEDED,
    FAILED,
    CANCELLED
}
