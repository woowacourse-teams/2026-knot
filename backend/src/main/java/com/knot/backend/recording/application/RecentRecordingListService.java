package com.knot.backend.recording.application;

import com.knot.backend.recording.application.dto.query.RecentRecordingListParameters;
import com.knot.backend.recording.application.dto.result.RecentRecordingItemResult;
import com.knot.backend.recording.application.dto.result.RecentRecordingListResult;
import com.knot.backend.recording.domain.model.RecentRecordingCursor;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class RecentRecordingListService {
    private final RecordingAccess recordingAccess;
    private final RecentRecordingQuery recentRecordingQuery;

    @Transactional(readOnly = true)
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
                    fetched,
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
                items,
                nextCursor(
                        workspaceId,
                        items.getLast()
                )
        );
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
