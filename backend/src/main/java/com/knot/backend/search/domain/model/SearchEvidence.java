package com.knot.backend.search.domain.model;

import com.knot.backend.search.domain.SearchErrorCode;
import com.knot.backend.search.domain.SearchException;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;

@Getter
@Entity
@Table(name = "search_evidences")
public class SearchEvidence {
    @EmbeddedId
    private SearchEvidenceId id;

    @Column(nullable = false)
    private int rank;

    @Column(name = "live_document_id", updatable = false)
    private Long liveDocumentId;

    @Column(name = "source_version", length = 43, updatable = false)
    private String sourceVersion;

    protected SearchEvidence() {}

    public static SearchEvidence create(
            long messageId,
            long documentId,
            int rank
    ) {
        return create(
                messageId,
                documentId,
                rank,
                null
        );
    }

    public static SearchEvidence create(
            long messageId,
            long documentId,
            int rank,
            String sourceVersion
    ) {
        if (rank < 1) {
            throw new SearchException(SearchErrorCode.INVALID_SEARCH_STATE);
        }
        SearchEvidence evidence = new SearchEvidence();
        evidence.id = SearchEvidenceId.of(
                messageId,
                documentId
        );
        evidence.rank = rank;
        evidence.liveDocumentId = documentId;
        evidence.sourceVersion = sourceVersion;
        return evidence;
    }
}
