package com.knot.backend.recording.application;

import com.knot.backend.recording.application.dto.command.RecordingControlCommand;
import com.knot.backend.recording.domain.RecordingErrorCode;
import com.knot.backend.recording.domain.RecordingException;
import com.knot.backend.recording.domain.model.RecordingSession;
import com.knot.backend.recording.domain.repository.RecordingSessionRepository;
import com.knot.backend.workspace.domain.WorkspaceErrorCode;
import com.knot.backend.workspace.domain.WorkspaceException;
import com.knot.backend.workspace.domain.repository.WorkspaceMemberRepository;
import com.knot.backend.workspace.domain.repository.WorkspaceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class RecordingAccess {
    private final WorkspaceRepository workspaceRepository;
    private final WorkspaceMemberRepository workspaceMemberRepository;
    private final RecordingSessionRepository recordingSessionRepository;
    private final RecordingControlTokenHasher recordingControlTokenHasher;

    public void validateMember(
            long workspaceId,
            long memberId
    ) {
        validateWorkspaceId(workspaceId);
        workspaceRepository.findById(workspaceId)
                .orElseThrow(() -> new WorkspaceException(WorkspaceErrorCode.WORKSPACE_NOT_FOUND));
        validateMembership(
                workspaceId,
                memberId
        );
    }

    public void lockMember(
            long workspaceId,
            long memberId
    ) {
        validateWorkspaceId(workspaceId);
        workspaceRepository.findByIdForUpdate(workspaceId)
                .orElseThrow(() -> new WorkspaceException(WorkspaceErrorCode.WORKSPACE_NOT_FOUND));
        validateMembership(
                workspaceId,
                memberId
        );
    }

    public RecordingSession loadOwnedForUpdate(
            long workspaceId,
            long memberId,
            long recordingId
    ) {
        RecordingSession session = loadForUpdate(
                workspaceId,
                memberId,
                recordingId
        );
        session.validateControlledBy(
                workspaceId,
                memberId
        );
        return session;
    }

    public RecordingSession loadWorkspaceRecordingForUpdate(
            long workspaceId,
            long memberId,
            long recordingId
    ) {
        RecordingSession session = loadForUpdate(
                workspaceId,
                memberId,
                recordingId
        );
        session.validateBelongsTo(workspaceId);
        return session;
    }

    private RecordingSession loadForUpdate(
            long workspaceId,
            long memberId,
            long recordingId
    ) {
        lockMember(
                workspaceId,
                memberId
        );
        if (recordingId <= 0) {
            throw new RecordingException(RecordingErrorCode.INVALID_RECORDING_DATA);
        }
        return recordingSessionRepository.findByIdForUpdate(recordingId)
                .orElseThrow(() -> new RecordingException(RecordingErrorCode.RECORDING_NOT_FOUND));
    }

    public RecordingSession loadControlledForUpdate(
            long workspaceId,
            long memberId,
            long recordingId,
            RecordingControlCommand command
    ) {
        RecordingSession session = loadOwnedForUpdate(
                workspaceId,
                memberId,
                recordingId
        );
        session.validateControlProof(
                command.tabId(),
                recordingControlTokenHasher.hash(command.controlToken())
        );
        return session;
    }

    private void validateWorkspaceId(long workspaceId) {
        if (workspaceId <= 0) {
            throw new WorkspaceException(WorkspaceErrorCode.INVALID_WORKSPACE_ID);
        }
    }

    private void validateMembership(
            long workspaceId,
            long memberId
    ) {
        if (!workspaceMemberRepository.existsByWorkspaceIdAndMemberId(
                workspaceId,
                memberId
        )) {
            throw new WorkspaceException(WorkspaceErrorCode.WORKSPACE_ACCESS_DENIED);
        }
    }
}
