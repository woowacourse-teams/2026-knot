package com.knot.backend.search.domain.model.notion;

public enum SearchWriteCardStatus {
    PENDING,
    RUNNING,
    DONE,
    CANCELED,
    FAILED;

    public boolean isTerminal() {
        return this == DONE || this == CANCELED || this == FAILED;
    }
}
