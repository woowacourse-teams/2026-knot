package com.knot.backend.document.application;

import com.knot.backend.document.application.dto.result.DocumentRecordingItemResult;
import com.knot.backend.document.application.dto.result.DocumentRecordingListResult;
import com.knot.backend.recording.domain.model.RecentRecordingCursor;
import com.knot.backend.workspace.domain.WorkspaceErrorCode;
import com.knot.backend.workspace.domain.WorkspaceException;
import com.knot.backend.workspace.domain.repository.WorkspaceMemberRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class DocumentRecordingListService {
    private static final int PAGE_SIZE = 20;

    private final WorkspaceMemberRepository workspaceMembers;
    private final DocumentRecordingListQuery documentRecordingListQuery;

    // 녹음 묶음과 그 문서를 같은 스냅샷에서 읽는다.
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public DocumentRecordingListResult find(
            long workspaceId,
            long memberId,
            String cursor
    ) {
        validateMember(
                workspaceId,
                memberId
        );
        List<DocumentRecordingItemResult> fetched = documentRecordingListQuery.findPage(
                workspaceId,
                parseCursor(
                        cursor,
                        workspaceId
                ),
                PAGE_SIZE + 1
        );
        if (fetched.size() <= PAGE_SIZE) {
            return new DocumentRecordingListResult(
                    fetched,
                    null
            );
        }
        List<DocumentRecordingItemResult> items = List.copyOf(
                fetched.subList(
                        0,
                        PAGE_SIZE
                )
        );
        return new DocumentRecordingListResult(
                items,
                nextCursor(
                        workspaceId,
                        items.getLast()
                )
        );
    }

    private void validateMember(
            long workspaceId,
            long memberId
    ) {
        if (!workspaceMembers.existsByWorkspaceIdAndMemberId(
                workspaceId,
                memberId
        )) {
            throw new WorkspaceException(WorkspaceErrorCode.WORKSPACE_ACCESS_DENIED);
        }
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
            DocumentRecordingItemResult last
    ) {
        return RecentRecordingCursor.of(
                workspaceId,
                last.startedAt(),
                last.recordingId()
        )
                .encode();
    }
}
