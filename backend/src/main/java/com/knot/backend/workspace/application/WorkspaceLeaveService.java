package com.knot.backend.workspace.application;

import com.knot.backend.document.application.DocumentArchivalService;
import com.knot.backend.recording.domain.model.RecordingSession;
import com.knot.backend.recording.domain.repository.RecordingSessionRepository;
import com.knot.backend.workspace.application.dto.result.WorkspaceOwnershipTransferCandidateResult;
import com.knot.backend.workspace.domain.model.Workspace;
import com.knot.backend.workspace.domain.WorkspaceErrorCode;
import com.knot.backend.workspace.domain.WorkspaceException;
import com.knot.backend.workspace.domain.model.WorkspaceMember;
import com.knot.backend.workspace.domain.repository.WorkspaceMemberRepository;
import com.knot.backend.workspace.domain.model.WorkspaceMemberRole;
import com.knot.backend.workspace.domain.repository.WorkspaceRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class WorkspaceLeaveService {
    private final WorkspaceRepository workspaceRepository;
    private final WorkspaceMemberRepository workspaceMemberRepository;
    private final WorkspaceOwnershipTransferCandidateQuery workspaceOwnershipTransferCandidateQuery;
    private final RecordingSessionRepository recordingSessionRepository;
    private final DocumentArchivalService documentArchivalService;
    private final Clock clock;

    @Transactional
    public void leave(
            long memberId,
            Long workspaceId
    ) {
        WorkspaceLog.Scope log = WorkspaceLog.begin(
                "workspace.leave",
                workspaceId
        );
        log.run(() -> {
            Workspace workspace = log.step(
                    "lock_workspace",
                    () -> lockWorkspace(workspaceId)
            );
            WorkspaceMember leavingMember = log.step(
                    "load_membership",
                    () -> workspaceMemberRepository.findLatestByWorkspaceIdAndMemberIdForUpdate(
                            workspaceId,
                            memberId
                    )
                            .orElseThrow(() -> new WorkspaceException(WorkspaceErrorCode.WORKSPACE_ACCESS_DENIED))
            );
            if (!leavingMember.isActive()) {
                log.outcome("replayed");
                return;
            }
            Instant leftAt = now();
            long activeMemberCount = log.step(
                    "count_active_members",
                    () -> workspaceMemberRepository.countActiveByWorkspaceId(workspaceId)
            );
            log.runStep(
                    "change_membership",
                    () -> workspace.removeMember(
                            leavingMember,
                            activeMemberCount,
                            leftAt
                    )
            );
            log.step(
                    "save_workspace",
                    () -> workspaceRepository.save(workspace)
            );
            log.step(
                    "save_membership",
                    () -> workspaceMemberRepository.save(leavingMember)
            );
            List<RecordingSession> recordings = log.step(
                    "load_recordings",
                    () -> findRecordingsToDiscard(
                            workspace,
                            workspaceId,
                            memberId
                    )
            );
            completeDeparture(
                    workspaceId,
                    memberId,
                    recordings,
                    leftAt,
                    log,
                    discardReason(workspace)
            );
            log.outcome(leaveOutcome(workspace));
        });
    }

    private String discardReason(Workspace workspace) {
        if (workspace.isDeleted()) {
            return "WORKSPACE_DELETED";
        }
        return "WORKSPACE_MEMBER_LEFT";
    }

    private String leaveOutcome(Workspace workspace) {
        if (workspace.isDeleted()) {
            return "left_and_workspace_deleted";
        }
        return "left";
    }

    @Transactional
    public void transferOwnershipAndLeave(
            long memberId,
            Long workspaceId,
            long successorMemberId
    ) {
        WorkspaceLog.Scope log = WorkspaceLog.begin(
                "workspace.ownership_transfer_and_leave",
                workspaceId
        );
        log.run(() -> {
            log.step(
                    "lock_workspace",
                    () -> lockWorkspace(workspaceId)
            );
            List<WorkspaceMember> transferMembers = log.step(
                    "load_memberships",
                    () -> workspaceMemberRepository.findAllActiveByWorkspaceIdAndMemberIdsForUpdate(
                            workspaceId,
                            List.of(
                                    memberId,
                                    successorMemberId
                            )
                    )
            );
            WorkspaceMember owner = log.step(
                    "validate_owner",
                    () -> findOwner(
                            transferMembers,
                            memberId
                    )
            );
            WorkspaceMember successor = log.step(
                    "validate_successor",
                    () -> findSuccessor(
                            transferMembers,
                            successorMemberId
                    )
            );
            Instant leftAt = now();
            log.runStep(
                    "change_ownership",
                    () -> owner.transferOwnershipAndLeave(
                            successor,
                            leftAt
                    )
            );
            log.step(
                    "save_memberships",
                    () -> workspaceMemberRepository.saveAll(
                            List.of(
                                    owner,
                                    successor
                            )
                    )
            );
            List<RecordingSession> recordings = log.step(
                    "load_recordings",
                    () -> recordingSessionRepository.findAllActiveByWorkspaceIdAndMemberIdForUpdate(
                            workspaceId,
                            memberId
                    )
            );
            completeDeparture(
                    workspaceId,
                    memberId,
                    recordings,
                    leftAt,
                    log,
                    "WORKSPACE_OWNERSHIP_TRANSFER_AND_LEFT"
            );
            log.outcome("ownership_transferred_and_left");
        });
    }

    @Transactional(readOnly = true)
    public List<WorkspaceOwnershipTransferCandidateResult> findOwnershipTransferCandidates(
            Long workspaceId,
            long memberId
    ) {
        WorkspaceLog.Scope log = WorkspaceLog.begin(
                "workspace.ownership_candidates",
                workspaceId
        );
        return log.call(() -> {
            log.runStep(
                    "validate_workspace_id",
                    () -> validateWorkspaceId(workspaceId)
            );
            log.step(
                    "load_workspace",
                    () -> workspaceRepository.findById(workspaceId)
                            .orElseThrow(() -> new WorkspaceException(WorkspaceErrorCode.WORKSPACE_NOT_FOUND))
            );
            log.runStep(
                    "validate_owner",
                    () -> {
                        if (!workspaceMemberRepository.existsByWorkspaceIdAndMemberIdAndRole(
                                workspaceId,
                                memberId,
                                WorkspaceMemberRole.OWNER
                        )) {
                            throw new WorkspaceException(WorkspaceErrorCode.WORKSPACE_OWNER_REQUIRED);
                        }
                    }
            );
            List<WorkspaceOwnershipTransferCandidateResult> results = log.step(
                    "load_candidates",
                    () -> workspaceOwnershipTransferCandidateQuery.findAll(
                            workspaceId,
                            memberId
                    )
            );
            log.outcome("completed");
            return results;
        });
    }

    private Workspace lockWorkspace(Long workspaceId) {
        validateWorkspaceId(workspaceId);
        return workspaceRepository.findByIdForUpdate(workspaceId)
                .orElseThrow(() -> new WorkspaceException(WorkspaceErrorCode.WORKSPACE_NOT_FOUND));
    }

    private WorkspaceMember findOwner(
            List<WorkspaceMember> transferMembers,
            long memberId
    ) {
        return transferMembers.stream()
                .filter(transferMember -> transferMember.getMemberId() == memberId)
                .filter(WorkspaceMember::hasOwnerRole)
                .findFirst()
                .orElseThrow(() -> new WorkspaceException(WorkspaceErrorCode.WORKSPACE_OWNER_REQUIRED));
    }

    private WorkspaceMember findSuccessor(
            List<WorkspaceMember> transferMembers,
            long successorMemberId
    ) {
        return transferMembers.stream()
                .filter(transferMember -> transferMember.getMemberId() == successorMemberId)
                .findFirst()
                .orElseThrow(
                        () -> new WorkspaceException(WorkspaceErrorCode.WORKSPACE_OWNERSHIP_TRANSFER_TARGET_CONFLICT)
                );
    }

    private List<RecordingSession> findRecordingsToDiscard(
            Workspace workspace,
            long workspaceId,
            long memberId
    ) {
        if (workspace.isDeleted()) {
            return recordingSessionRepository.findAllActiveByWorkspaceIdForUpdate(workspaceId);
        }
        return recordingSessionRepository.findAllActiveByWorkspaceIdAndMemberIdForUpdate(
                workspaceId,
                memberId
        );
    }

    private void completeDeparture(
            long workspaceId,
            long memberId,
            List<RecordingSession> recordingsToDiscard,
            Instant leftAt,
            WorkspaceLog.Scope log,
            String reason
    ) {
        log.runStep(
                "discard_recordings",
                () -> {
                    for (RecordingSession recording : recordingsToDiscard) {
                        WorkspaceLog.Scope discard = WorkspaceLog.discard(
                                workspaceId,
                                recording.getId(),
                                reason
                        );
                        discard.run(() -> {
                            discard.runStep(
                                    "change_state",
                                    () -> recording.discard(leftAt)
                            );
                            discard.step(
                                    "save_recording",
                                    () -> recordingSessionRepository.save(recording)
                            );
                            discard.outcome("discarded");
                        });
                    }
                }
        );
        log.runStep(
                "flush_memberships",
                workspaceMemberRepository::flush
        );
        log.runStep(
                "archive_documents",
                () -> documentArchivalService.archiveAfterMemberDeparture(
                        workspaceId,
                        memberId,
                        leftAt
                )
        );
    }

    private void validateWorkspaceId(Long workspaceId) {
        if (workspaceId == null || workspaceId <= 0) {
            throw new WorkspaceException(WorkspaceErrorCode.INVALID_WORKSPACE_ID);
        }
    }

    private Instant now() {
        return clock.instant()
                .truncatedTo(ChronoUnit.MICROS);
    }
}
