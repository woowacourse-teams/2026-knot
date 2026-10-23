package com.knot.backend.document.infrastructure.persistence;

import com.knot.backend.document.domain.model.Document;
import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;

interface DocumentListJpaRepository extends Repository<Document, Long> {

    @Query("""
            select new com.knot.backend.document.infrastructure.persistence.DocumentTopicRow(coalesce(category.name, d.topic), count(d.id))
            from Document d
            left join DocumentCategory category on category.id = d.categoryId and category.workspaceId = d.workspaceId
            left join DocumentConfirmation mine
                on mine.id.documentId = d.id and mine.id.memberId = :memberId
            where d.workspaceId = :workspaceId
                and (:recordingSessionId is null or d.recordingSessionId = :recordingSessionId)
                and (:confirmationState = 'ALL'
                    or (:confirmationState = 'PENDING' and mine.id.documentId is not null and mine.confirmedAt is null)
                    or (:confirmationState = 'CONFIRMED' and mine.confirmedAt is not null)
                    or (:confirmationState = 'NOT_REQUIRED' and mine.id.documentId is null))
            group by coalesce(category.name, d.topic)
            order by coalesce(category.name, d.topic)
            """)
    List<DocumentTopicRow> findTopics(
            long workspaceId,
            long memberId,
            Long recordingSessionId,
            String confirmationState
    );

    @Query("""
            select new com.knot.backend.document.infrastructure.persistence.DocumentTopicRow(coalesce(category.name, d.topic), count(d.id))
            from Document d
            left join DocumentCategory category on category.id = d.categoryId and category.workspaceId = d.workspaceId
            join DocumentConfirmation mine
                on mine.id.documentId = d.id and mine.id.memberId = :memberId
            where d.workspaceId = :workspaceId and mine.confirmedAt is null
                and (:recordingSessionId is null or d.recordingSessionId = :recordingSessionId)
            group by coalesce(category.name, d.topic)
            order by coalesce(category.name, d.topic)
            """)
    List<DocumentTopicRow> findPendingTopics(
            long workspaceId,
            long memberId,
            Long recordingSessionId
    );

    @Query("""
            select new com.knot.backend.document.infrastructure.persistence.DocumentCardRow(
                d.id, d.recordingSessionId, coalesce(category.name, d.topic), d.title, d.summary, d.status, d.createdAt,
                rs.accumulatedRecordingMillis,
                case when mine.id.documentId is not null then true else false end, mine.confirmedAt
            )
            from Document d
            left join DocumentCategory category on category.id = d.categoryId and category.workspaceId = d.workspaceId
            join RecordingSession rs on rs.id = d.recordingSessionId
            left join DocumentConfirmation mine
                on mine.id.documentId = d.id and mine.id.memberId = :memberId
            where d.workspaceId = :workspaceId
                and (:recordingSessionId is null or d.recordingSessionId = :recordingSessionId)
                and (:confirmationState = 'ALL'
                    or (:confirmationState = 'PENDING' and mine.id.documentId is not null and mine.confirmedAt is null)
                    or (:confirmationState = 'CONFIRMED' and mine.confirmedAt is not null)
                    or (:confirmationState = 'NOT_REQUIRED' and mine.id.documentId is null))
                and (:afterCursor = false or d.createdAt < :cursorTime
                    or (d.createdAt = :cursorTime and d.id < :cursorId))
            order by d.createdAt desc, d.id desc
            """)
    List<DocumentCardRow> findPage(
            long workspaceId,
            long memberId,
            Long recordingSessionId,
            String confirmationState,
            boolean afterCursor,
            Instant cursorTime,
            long cursorId,
            Pageable pageable
    );

    // 필수 녹음 세션 FK가 조인 누락을 막으므로, 후보를 페이지 크기로 제한한 뒤 녹음 정보를 읽는다.
    @Query("""
            with pending_candidates as materialized (
                select d.id as id, d.recordingSessionId as recordingSessionId,
                    coalesce(category.name, d.topic) as topic, d.title as title, d.summary as summary,
                    d.status as status, d.createdAt as createdAt, mine.confirmedAt as confirmedAt
                from Document d
                left join DocumentCategory category on category.id = d.categoryId and category.workspaceId = d.workspaceId
                join DocumentConfirmation mine
                    on mine.id.documentId = d.id and mine.id.memberId = :memberId
                where d.workspaceId = :workspaceId and mine.confirmedAt is null
                    and (:recordingSessionId is null or d.recordingSessionId = :recordingSessionId)
                    and (:afterCursor = false or d.createdAt < :cursorTime
                        or (d.createdAt = :cursorTime and d.id < :cursorId))
                order by d.createdAt desc, d.id desc
                limit :pageLimit
            )
            select new com.knot.backend.document.infrastructure.persistence.DocumentCardRow(
                candidate.id, candidate.recordingSessionId, candidate.topic, candidate.title,
                candidate.summary, candidate.status, candidate.createdAt,
                rs.accumulatedRecordingMillis, true, candidate.confirmedAt
            )
            from pending_candidates candidate
            join RecordingSession rs on rs.id = candidate.recordingSessionId
            order by candidate.createdAt desc, candidate.id desc
            """)
    List<DocumentCardRow> findPendingPage(
            long workspaceId,
            long memberId,
            Long recordingSessionId,
            boolean afterCursor,
            Instant cursorTime,
            long cursorId,
            int pageLimit
    );

    @Query("""
            select new com.knot.backend.document.infrastructure.persistence.DocumentCardCountsRow(
                d.id,
                sum(case when c.confirmedAt is not null then 1 else 0 end),
                sum(case when c.id.documentId is not null and c.confirmedAt is null
                    and active.id is not null then 1 else 0 end),
                sum(case when c.id.documentId is not null and c.confirmedAt is null
                    and active.id is null then 1 else 0 end)
            )
            from Document d
            left join DocumentConfirmation c on c.id.documentId = d.id
            left join WorkspaceMember active
                on active.workspaceId = d.workspaceId and active.memberId = c.id.memberId
                    and active.leftAt is null
            where d.workspaceId = :workspaceId and d.id in :documentIds
            group by d.id
            """)
    List<DocumentCardCountsRow> findCounts(
            long workspaceId,
            List<Long> documentIds
    );
}
