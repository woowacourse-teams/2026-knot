package com.knot.backend.search.domain.model.notion;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.io.Serializable;

@Embeddable
public record SearchNotionEvidenceId(
        @Column(name = "message_id", nullable = false) long messageId,
        @Column(nullable = false) int rank
) implements Serializable {
}
