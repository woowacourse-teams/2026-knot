package com.knot.backend.recording.infrastructure.persistence;

import com.knot.backend.recording.application.TeammateRecordingQuery;
import com.knot.backend.recording.application.dto.result.TeammateRecordingCandidate;
import com.knot.backend.recording.domain.model.RecordingStatus;
import jakarta.persistence.EntityManager;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class TeammateRecordingQueryAdapter implements TeammateRecordingQuery {
    private static final String IN_PROGRESS_JPQL = """
            SELECT new com.knot.backend.recording.application.dto.result.TeammateRecordingCandidate(s, m.nickname)
            FROM RecordingSession s
            JOIN Member m ON m.id = s.memberId
            WHERE s.workspaceId = :workspaceId
                AND s.memberId <> :excludedMemberId
                AND s.status IN :statuses
            ORDER BY s.startedAt DESC, s.id DESC
            """;

    private final EntityManager entityManager;

    @Override
    public List<TeammateRecordingCandidate> findInProgress(
            long workspaceId,
            long excludedMemberId
    ) {
        return entityManager.createQuery(
                IN_PROGRESS_JPQL,
                TeammateRecordingCandidate.class
        )
                .setParameter(
                        "workspaceId",
                        workspaceId
                )
                .setParameter(
                        "excludedMemberId",
                        excludedMemberId
                )
                .setParameter(
                        "statuses",
                        RecordingStatus.ACTIVE_STATUSES
                )
                .getResultList();
    }
}
