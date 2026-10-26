package com.knot.backend.recording.application;

import com.knot.backend.recording.application.dto.query.RecentRecordingListParameters;
import com.knot.backend.recording.application.dto.result.RecentRecordingItemResult;
import com.knot.backend.recording.application.dto.result.RecentRecordingListResult;
import com.knot.backend.recording.application.dto.result.RecentRecordingRowResult;
import com.knot.backend.recording.application.dto.result.RecordingDocumentProcessingResult;
import com.knot.backend.recording.domain.model.RecentRecordingCursor;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class RecentRecordingListService {
    private final RecordingAccess recordingAccess;
    private final RecentRecordingQuery recentRecordingQuery;
    private final RecordingDocumentProcessing documentProcessing;

    // 행과 처리 상태를 같은 읽기 시점에서 읽는다. 조회는 녹음·전사·문서 정리 상태를 바꾸지 않는다.
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public RecentRecordingListResult find(
            long workspaceId,
            long memberId,
            RecentRecordingListParameters parameters
    ) {
        recordingAccess.validateMember(
                workspaceId,
                memberId
        );
        List<RecentRecordingItemResult> fetched = recentRecordingQuery.findPage(
                workspaceId,
                parseCursor(
                        parameters.cursor(),
                        workspaceId
                ),
                parameters.size() + 1
        );
        if (fetched.size() <= parameters.size()) {
            return new RecentRecordingListResult(
                    rows(
                            workspaceId,
                            fetched
                    ),
                    null
            );
        }
        List<RecentRecordingItemResult> items = List.copyOf(
                fetched.subList(
                        0,
                        parameters.size()
                )
        );
        return new RecentRecordingListResult(
                rows(
                        workspaceId,
                        items
                ),
                nextCursor(
                        workspaceId,
                        items.getLast()
                )
        );
    }

    private List<RecentRecordingRowResult> rows(
            long workspaceId,
            List<RecentRecordingItemResult> items
    ) {
        Map<Long, RecordingDocumentProcessingResult> processing = documentProcessing.findAll(
                workspaceId,
                items.stream()
                        .map(RecentRecordingItemResult::recordingId)
                        .toList()
        );
        return items.stream()
                .map(
                        item -> RecentRecordingRowResult.of(
                                item,
                                processing.get(item.recordingId())
                        )
                )
                .toList();
    }

    private RecentRecordingCursor parseCursor(
            String encoded,
            long workspaceId
    ) {
        if (encoded == null) {
            return null;
        }
        return RecentRecordingCursor.parse(
                encoded,
                workspaceId
        );
    }

    private String nextCursor(
            long workspaceId,
            RecentRecordingItemResult last
    ) {
        return RecentRecordingCursor.of(
                workspaceId,
                last.startedAt(),
                last.recordingId()
        )
                .encode();
    }
}
