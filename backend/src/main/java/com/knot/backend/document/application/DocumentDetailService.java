package com.knot.backend.document.application;

import com.knot.backend.document.application.dto.result.DocumentDetailResult;
import com.knot.backend.document.domain.DocumentErrorCode;
import com.knot.backend.document.domain.DocumentException;
import com.knot.backend.workspace.domain.WorkspaceErrorCode;
import com.knot.backend.workspace.domain.WorkspaceException;
import com.knot.backend.workspace.domain.repository.WorkspaceMemberRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class DocumentDetailService {
    private final WorkspaceMemberRepository workspaceMembers;
    private final DocumentDetailQuery query;
    private final com.knot.backend.recording.application.RecordingAudioFinalizationQuery audioQuality;

    public DocumentDetailResult find(
            long workspaceId,
            long memberId,
            long documentId
    ) {
        if (!workspaceMembers.existsByWorkspaceIdAndMemberId(
                workspaceId,
                memberId
        )) {
            throw new WorkspaceException(WorkspaceErrorCode.WORKSPACE_ACCESS_DENIED);
        }
        return query.find(
                workspaceId,
                documentId,
                memberId
        )
                .map(DocumentDetailResult::from)
                .map(result -> result.withSourceAudio(audioQuality.sourceQuality(result.recordingSessionId())))
                .orElseThrow(() -> new DocumentException(DocumentErrorCode.DOCUMENT_NOT_FOUND));
    }
}
