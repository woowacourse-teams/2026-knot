package com.knot.backend.workspace.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import com.knot.backend.document.application.DocumentArchivalService;
import com.knot.backend.recording.domain.model.RecordingSession;
import com.knot.backend.recording.domain.repository.RecordingSessionRepository;
import com.knot.backend.recording.domain.model.RecordingStatus;
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
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InOrder;

class WorkspaceLeaveServiceTest {
    private static final Instant JOINED_AT = Instant.parse("2026-09-30T00:00:00Z");
    private static final Instant NOW = Instant.parse("2026-09-30T00:01:00.123456789Z");
    private static final Instant LEFT_AT = Instant.parse("2026-09-30T00:01:00.123456Z");

    private final WorkspaceRepository workspaceRepository = mock(WorkspaceRepository.class);
    private final WorkspaceMemberRepository workspaceMemberRepository = mock(WorkspaceMemberRepository.class);
    private final WorkspaceOwnershipTransferCandidateQuery workspaceOwnershipTransferCandidateQuery = mock(
            WorkspaceOwnershipTransferCandidateQuery.class
    );
    private final RecordingSessionRepository recordingSessionRepository = mock(RecordingSessionRepository.class);
    private final DocumentArchivalService documentArchivalService = mock(DocumentArchivalService.class);
    private final WorkspaceLeaveService service = new WorkspaceLeaveService(
            workspaceRepository,
            workspaceMemberRepository,
            workspaceOwnershipTransferCandidateQuery,
            recordingSessionRepository,
            documentArchivalService,
            Clock.fixed(
                    NOW,
                    ZoneOffset.UTC
            ),
            mock(com.knot.backend.recording.application.RecordingAudioCleanupStateService.class)
    );

    @DisplayName("다른 멤버가 남는 탈퇴는 멤버십을 저장하고 탈퇴자의 진행 녹음만 폐기한 뒤 문서를 보관한다")
    @Test
    void leave_success_member() {
        // given
        Workspace workspace = workspace();
        WorkspaceMember actor = participant(
                2L,
                WorkspaceMemberRole.MEMBER
        );
        RecordingSession recording = recordingSession();
        stubLeave(
                workspace,
                actor,
                2L
        );
        when(
                recordingSessionRepository.findAllActiveByWorkspaceIdAndMemberIdForUpdate(
                        1L,
                        2L
                )
        ).thenReturn(List.of(recording));

        // when
        service.leave(
                2L,
                1L
        );

        // then
        assertThat(actor.getLeftAt()).isEqualTo(LEFT_AT);
        assertThat(workspace.isDeleted()).isFalse();
        assertThat(recording.getStatus()).isEqualTo(RecordingStatus.DISCARDED);
        verify(
                recordingSessionRepository,
                never()
        ).findAllActiveByWorkspaceIdForUpdate(1L);
        InOrder order = inOrder(
                workspaceRepository,
                workspaceMemberRepository,
                recordingSessionRepository,
                documentArchivalService
        );
        order.verify(workspaceRepository)
                .findByIdForUpdate(1L);
        order.verify(workspaceMemberRepository)
                .countActiveByWorkspaceId(1L);
        order.verify(workspaceMemberRepository)
                .save(actor);
        order.verify(recordingSessionRepository)
                .save(recording);
        order.verify(workspaceMemberRepository)
                .flush();
        order.verify(documentArchivalService)
                .archiveAfterMemberDeparture(
                        1L,
                        2L,
                        LEFT_AT
                );
    }

    @DisplayName("마지막 멤버가 탈퇴해 워크스페이스가 삭제되면 워크스페이스의 모든 진행 녹음을 폐기한다")
    @Test
    void leave_success_lastActiveMemberDiscardsWorkspaceRecordings() {
        // given
        Workspace workspace = workspace();
        RecordingSession recording = recordingSession();
        stubLeave(
                workspace,
                participant(
                        2L,
                        WorkspaceMemberRole.OWNER
                ),
                1L
        );
        when(recordingSessionRepository.findAllActiveByWorkspaceIdForUpdate(1L)).thenReturn(List.of(recording));

        // when
        service.leave(
                2L,
                1L
        );

        // then
        assertThat(workspace.getDeletedAt()).isEqualTo(LEFT_AT);
        assertThat(recording.getStatus()).isEqualTo(RecordingStatus.DISCARDED);
        verify(workspaceRepository).save(workspace);
        verify(
                recordingSessionRepository,
                never()
        ).findAllActiveByWorkspaceIdAndMemberIdForUpdate(
                1L,
                2L
        );
    }

    @DisplayName("이미 탈퇴한 멤버십의 재요청은 활성 멤버 수를 세지 않고 아무것도 바꾸지 않는다")
    @Test
    void leave_success_alreadyLeft() {
        // given
        WorkspaceMember actor = participant(
                2L,
                WorkspaceMemberRole.MEMBER
        );
        actor.leave(
                LEFT_AT,
                2L
        );
        when(workspaceRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(workspace()));
        when(
                workspaceMemberRepository.findLatestByWorkspaceIdAndMemberIdForUpdate(
                        1L,
                        2L
                )
        ).thenReturn(Optional.of(actor));

        // when
        service.leave(
                2L,
                1L
        );

        // then
        verify(
                workspaceMemberRepository,
                never()
        ).countActiveByWorkspaceId(1L);
        verify(
                workspaceMemberRepository,
                never()
        ).save(any(WorkspaceMember.class));
        verifyNoInteractions(
                recordingSessionRepository,
                documentArchivalService
        );
    }

    @DisplayName("도메인이 탈퇴를 거부하면 저장·녹음 폐기·문서 보관을 하지 않는다")
    @Test
    void leave_failure_domainRejected() {
        // given
        stubLeave(
                workspace(),
                participant(
                        2L,
                        WorkspaceMemberRole.OWNER
                ),
                2L
        );

        // when
        ThrowingCallable action = () -> service.leave(
                2L,
                1L
        );

        // then
        assertError(
                action,
                WorkspaceErrorCode.WORKSPACE_OWNER_TRANSFER_REQUIRED
        );
        verify(
                workspaceMemberRepository,
                never()
        ).save(any(WorkspaceMember.class));
        verifyNoInteractions(
                recordingSessionRepository,
                documentArchivalService
        );
    }

    @DisplayName("문서 보관 오류를 호출자에게 전달해 탈퇴 트랜잭션이 롤백되도록 한다")
    @Test
    void leave_failure_documentArchivalFailed() {
        // given
        stubLeave(
                workspace(),
                participant(
                        2L,
                        WorkspaceMemberRole.MEMBER
                ),
                2L
        );
        IllegalStateException failure = new IllegalStateException("문서 보관 실패");
        doThrow(failure).when(documentArchivalService)
                .archiveAfterMemberDeparture(
                        1L,
                        2L,
                        LEFT_AT
                );

        // when
        ThrowingCallable action = () -> service.leave(
                2L,
                1L
        );

        // then
        assertThatThrownBy(action).isSameAs(failure);
    }

    @DisplayName("워크스페이스가 없거나 삭제되었으면 멤버십을 잠그지 않고 404 오류를 반환한다")
    @Test
    void leave_failure_workspaceNotFound() {
        // given
        when(workspaceRepository.findByIdForUpdate(1L)).thenReturn(Optional.empty());

        // when
        ThrowingCallable action = () -> service.leave(
                2L,
                1L
        );

        // then
        assertError(
                action,
                WorkspaceErrorCode.WORKSPACE_NOT_FOUND
        );
        verifyNoInteractions(workspaceMemberRepository);
    }

    @DisplayName("참여 이력이 없으면 활성 멤버 수를 세지 않고 403 오류를 반환한다")
    @Test
    void leave_failure_membershipHistoryNotFound() {
        // given
        when(workspaceRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(workspace()));
        when(
                workspaceMemberRepository.findLatestByWorkspaceIdAndMemberIdForUpdate(
                        1L,
                        2L
                )
        ).thenReturn(Optional.empty());

        // when
        ThrowingCallable action = () -> service.leave(
                2L,
                1L
        );

        // then
        assertError(
                action,
                WorkspaceErrorCode.WORKSPACE_ACCESS_DENIED
        );
        verify(
                workspaceMemberRepository,
                never()
        ).countActiveByWorkspaceId(1L);
    }

    @DisplayName("잘못된 Workspace 식별자는 저장소를 조회하지 않고 거절한다")
    @ParameterizedTest
    @NullSource
    @ValueSource(longs = {0L, -1L})
    void leave_failure_invalidWorkspaceId(Long workspaceId) {
        // given
        long memberId = 2L;

        // when
        ThrowingCallable action = () -> service.leave(
                memberId,
                workspaceId
        );

        // then
        assertError(
                action,
                WorkspaceErrorCode.INVALID_WORKSPACE_ID
        );
        verifyNoInteractions(
                workspaceRepository,
                workspaceMemberRepository
        );
    }

    @DisplayName("Workspace를 먼저 잠그고 승계 결과를 저장한 뒤 요청자 녹음 폐기와 문서 보관을 조정한다")
    @Test
    void transferOwnershipAndLeave_success() {
        // given
        WorkspaceMember owner = participant(
                2L,
                WorkspaceMemberRole.OWNER
        );
        WorkspaceMember successor = participant(
                3L,
                WorkspaceMemberRole.MEMBER
        );
        stubParticipants(
                List.of(
                        successor,
                        owner
                )
        );

        // when
        service.transferOwnershipAndLeave(
                2L,
                1L,
                3L
        );

        // then
        assertThat(owner.getLeftAt()).isEqualTo(LEFT_AT);
        assertThat(successor.getRole()).isEqualTo(WorkspaceMemberRole.OWNER);
        InOrder order = inOrder(
                workspaceRepository,
                workspaceMemberRepository,
                recordingSessionRepository,
                documentArchivalService
        );
        order.verify(workspaceRepository)
                .findByIdForUpdate(1L);
        order.verify(workspaceMemberRepository)
                .findAllActiveByWorkspaceIdAndMemberIdsForUpdate(
                        1L,
                        List.of(
                                2L,
                                3L
                        )
                );
        order.verify(workspaceMemberRepository)
                .saveAll(
                        List.of(
                                owner,
                                successor
                        )
                );
        order.verify(recordingSessionRepository)
                .findAllActiveByWorkspaceIdAndMemberIdForUpdate(
                        1L,
                        2L
                );
        order.verify(workspaceMemberRepository)
                .flush();
        order.verify(documentArchivalService)
                .archiveAfterMemberDeparture(
                        1L,
                        2L,
                        LEFT_AT
                );
    }

    @DisplayName("보관 실패를 전파해 소유권 승계와 OWNER 탈퇴가 함께 롤백되도록 한다")
    @Test
    void transferOwnershipAndLeave_failure_documentArchivalFailed() {
        // given
        stubParticipants(
                List.of(
                        participant(
                                2L,
                                WorkspaceMemberRole.OWNER
                        ),
                        participant(
                                3L,
                                WorkspaceMemberRole.MEMBER
                        )
                )
        );
        IllegalStateException failure = new IllegalStateException("문서 보관 실패");
        doThrow(failure).when(documentArchivalService)
                .archiveAfterMemberDeparture(
                        1L,
                        2L,
                        LEFT_AT
                );

        // when
        ThrowingCallable action = () -> service.transferOwnershipAndLeave(
                2L,
                1L,
                3L
        );

        // then
        assertThatThrownBy(action).isSameAs(failure);
    }

    @DisplayName("미존재 또는 삭제된 Workspace는 참여 행을 잠그지 않는다")
    @Test
    void transferOwnershipAndLeave_failure_workspaceNotFound() {
        // given
        when(workspaceRepository.findByIdForUpdate(1L)).thenReturn(Optional.empty());

        // when
        ThrowingCallable action = () -> service.transferOwnershipAndLeave(
                2L,
                1L,
                3L
        );

        // then
        assertError(
                action,
                WorkspaceErrorCode.WORKSPACE_NOT_FOUND
        );
        verifyNoInteractions(workspaceMemberRepository);
    }

    @DisplayName("요청자가 활성 OWNER로 조회되지 않으면 승계 대상을 찾기 전에 거절한다")
    @ParameterizedTest
    @ValueSource(strings = {"missing", "member"})
    void transferOwnershipAndLeave_failure_ownerRequired(String actorState) {
        // given
        WorkspaceMember successor = participant(
                3L,
                WorkspaceMemberRole.MEMBER
        );
        stubParticipants(
                participantsWithoutActiveOwner(
                        actorState,
                        successor
                )
        );

        // when
        ThrowingCallable action = () -> service.transferOwnershipAndLeave(
                2L,
                1L,
                3L
        );

        // then
        assertError(
                action,
                WorkspaceErrorCode.WORKSPACE_OWNER_REQUIRED
        );
        verify(
                workspaceMemberRepository,
                never()
        ).saveAll(any());
    }

    @DisplayName("승계 대상이 활성 참여자로 조회되지 않으면 기존 OWNER 상태를 유지한다")
    @Test
    void transferOwnershipAndLeave_failure_successorMissing() {
        // given
        WorkspaceMember owner = participant(
                2L,
                WorkspaceMemberRole.OWNER
        );
        stubParticipants(List.of(owner));

        // when
        ThrowingCallable action = () -> service.transferOwnershipAndLeave(
                2L,
                1L,
                3L
        );

        // then
        assertError(
                action,
                WorkspaceErrorCode.WORKSPACE_OWNERSHIP_TRANSFER_TARGET_CONFLICT
        );
        assertThat(owner.isActive()).isTrue();
        verify(
                workspaceMemberRepository,
                never()
        ).saveAll(any());
    }

    @DisplayName("OWNER에게 승계 후보를 조회해 반환한다")
    @Test
    void findOwnershipTransferCandidates_success() {
        // given
        List<WorkspaceOwnershipTransferCandidateResult> candidates = List.of(
                new WorkspaceOwnershipTransferCandidateResult(
                        3L,
                        "후보"
                )
        );
        when(workspaceRepository.findById(1L)).thenReturn(Optional.of(workspace()));
        when(
                workspaceMemberRepository.existsByWorkspaceIdAndMemberIdAndRole(
                        1L,
                        2L,
                        WorkspaceMemberRole.OWNER
                )
        ).thenReturn(true);
        when(
                workspaceOwnershipTransferCandidateQuery.findAll(
                        1L,
                        2L
                )
        ).thenReturn(candidates);

        // when
        List<WorkspaceOwnershipTransferCandidateResult> result = service.findOwnershipTransferCandidates(
                1L,
                2L
        );

        // then
        assertThat(result).isEqualTo(candidates);
    }

    @DisplayName("활성 OWNER가 아니면 승계 후보를 조회하지 않는다")
    @Test
    void findOwnershipTransferCandidates_failure_ownerRequired() {
        // given
        when(workspaceRepository.findById(1L)).thenReturn(Optional.of(workspace()));
        when(
                workspaceMemberRepository.existsByWorkspaceIdAndMemberIdAndRole(
                        1L,
                        2L,
                        WorkspaceMemberRole.OWNER
                )
        ).thenReturn(false);

        // when
        ThrowingCallable action = () -> service.findOwnershipTransferCandidates(
                1L,
                2L
        );

        // then
        assertError(
                action,
                WorkspaceErrorCode.WORKSPACE_OWNER_REQUIRED
        );
        verifyNoInteractions(workspaceOwnershipTransferCandidateQuery);
    }

    @DisplayName("워크스페이스가 없으면 OWNER 여부를 확인하지 않고 404 오류를 반환한다")
    @Test
    void findOwnershipTransferCandidates_failure_workspaceNotFound() {
        // given
        when(workspaceRepository.findById(1L)).thenReturn(Optional.empty());

        // when
        ThrowingCallable action = () -> service.findOwnershipTransferCandidates(
                1L,
                2L
        );

        // then
        assertError(
                action,
                WorkspaceErrorCode.WORKSPACE_NOT_FOUND
        );
        verifyNoInteractions(
                workspaceMemberRepository,
                workspaceOwnershipTransferCandidateQuery
        );
    }

    private void stubLeave(
            Workspace workspace,
            WorkspaceMember actor,
            long activeMemberCount
    ) {
        when(workspaceRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(workspace));
        when(
                workspaceMemberRepository.findLatestByWorkspaceIdAndMemberIdForUpdate(
                        1L,
                        2L
                )
        ).thenReturn(Optional.of(actor));
        when(workspaceMemberRepository.countActiveByWorkspaceId(1L)).thenReturn(activeMemberCount);
    }

    private void stubParticipants(List<WorkspaceMember> participants) {
        when(workspaceRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(workspace()));
        when(
                workspaceMemberRepository.findAllActiveByWorkspaceIdAndMemberIdsForUpdate(
                        1L,
                        List.of(
                                2L,
                                3L
                        )
                )
        ).thenReturn(participants);
    }

    private List<WorkspaceMember> participantsWithoutActiveOwner(
            String actorState,
            WorkspaceMember successor
    ) {
        if (actorState.equals("missing")) {
            return List.of(successor);
        }
        return List.of(
                participant(
                        2L,
                        WorkspaceMemberRole.MEMBER
                ),
                successor
        );
    }

    private WorkspaceMember participant(
            long memberId,
            WorkspaceMemberRole role
    ) {
        return WorkspaceMember.create(
                1L,
                memberId,
                role,
                JOINED_AT
        );
    }

    private Workspace workspace() {
        return Workspace.create(
                "Knot 팀",
                JOINED_AT
        );
    }

    private RecordingSession recordingSession() {
        return RecordingSession.start(
                1L,
                2L,
                UUID.randomUUID(),
                UUID.randomUUID(),
                "a".repeat(64),
                JOINED_AT
        );
    }

    private void assertError(
            ThrowingCallable action,
            WorkspaceErrorCode errorCode
    ) {
        assertThatThrownBy(action).isInstanceOf(WorkspaceException.class)
                .extracting(exception -> ((WorkspaceException) exception).getErrorCode())
                .isEqualTo(errorCode);
    }
}
