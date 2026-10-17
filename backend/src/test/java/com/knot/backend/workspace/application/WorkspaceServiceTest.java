package com.knot.backend.workspace.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.knot.backend.workspace.application.dto.result.WorkspaceDetailResult;
import com.knot.backend.workspace.application.dto.result.WorkspaceListResult;
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
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.test.util.ReflectionTestUtils;

class WorkspaceServiceTest {
    private static final Instant CREATED_AT = Instant.parse("2026-08-28T00:00:00Z");

    private final WorkspaceRepository workspaceRepository = mock(WorkspaceRepository.class);
    private final WorkspaceMemberRepository workspaceMemberRepository = mock(WorkspaceMemberRepository.class);
    private final WorkspaceDetailQuery workspaceDetailQuery = mock(WorkspaceDetailQuery.class);
    private final WorkspaceService service = new WorkspaceService(
            workspaceRepository,
            workspaceMemberRepository,
            workspaceDetailQuery,
            Clock.fixed(
                    CREATED_AT,
                    ZoneOffset.UTC
            )
    );

    @Test
    @DisplayName("워크스페이스를 생성하면 생성자를 OWNER 멤버십으로 저장하고 ID를 반환한다")
    void create_success() {
        // given
        when(workspaceRepository.save(any(Workspace.class))).thenAnswer(invocation -> {
            Workspace workspace = invocation.getArgument(0);
            ReflectionTestUtils.setField(
                    workspace,
                    "id",
                    7L
            );
            return workspace;
        });

        // when
        long workspaceId = service.create(
                3L,
                "Knot 팀"
        );

        // then
        ArgumentCaptor<Workspace> workspaceCaptor = ArgumentCaptor.forClass(Workspace.class);
        ArgumentCaptor<WorkspaceMember> memberCaptor = ArgumentCaptor.forClass(WorkspaceMember.class);
        verify(workspaceRepository).save(workspaceCaptor.capture());
        verify(workspaceMemberRepository).save(memberCaptor.capture());
        assertThat(workspaceId).isEqualTo(7L);
        assertThat(workspaceCaptor.getValue()).extracting(
                Workspace::getName,
                Workspace::getCreatedAt
        )
                .containsExactly(
                        "Knot 팀",
                        CREATED_AT
                );
        assertThat(memberCaptor.getValue()).extracting(
                WorkspaceMember::getWorkspaceId,
                WorkspaceMember::getMemberId,
                WorkspaceMember::getRole,
                WorkspaceMember::getJoinedAt
        )
                .containsExactly(
                        7L,
                        3L,
                        WorkspaceMemberRole.OWNER,
                        CREATED_AT
                );
    }

    @Test
    @DisplayName("워크스페이스 이름이 올바르지 않으면 아무것도 저장하지 않는다")
    void create_failure_invalidWorkspaceName() {
        // given
        String invalidName = "Knot!";

        // when
        ThrowingCallable action = () -> service.create(
                3L,
                invalidName
        );

        // then
        assertError(
                action,
                WorkspaceErrorCode.INVALID_WORKSPACE_NAME
        );
        verify(
                workspaceRepository,
                never()
        ).save(any(Workspace.class));
        verify(
                workspaceMemberRepository,
                never()
        ).save(any(WorkspaceMember.class));
    }

    @Test
    @DisplayName("워크스페이스 멤버는 이름과 내 역할, 활성 멤버 수를 조회한다")
    void findDetail_success() {
        // given
        WorkspaceDetailResult detail = new WorkspaceDetailResult(
                "Knot 팀",
                WorkspaceMemberRole.OWNER,
                2L
        );
        when(
                workspaceDetailQuery.find(
                        1L,
                        10L
                )
        ).thenReturn(Optional.of(detail));

        // when
        WorkspaceDetailResult result = service.findDetail(
                1L,
                10L
        );

        // then
        assertThat(result).isEqualTo(detail);
    }

    @Test
    @DisplayName("워크스페이스 ID가 양수가 아니면 조회를 거부한다")
    void findDetail_failure_invalidWorkspaceId() {
        // given
        Long invalidWorkspaceId = 0L;

        // when
        ThrowingCallable action = () -> service.findDetail(
                invalidWorkspaceId,
                10L
        );

        // then
        assertError(
                action,
                WorkspaceErrorCode.INVALID_WORKSPACE_ID
        );
        verifyNoInteractions(workspaceDetailQuery);
    }

    @Test
    @DisplayName("존재하지 않거나 삭제된 워크스페이스는 조회할 수 없다")
    void findDetail_failure_workspaceNotFound() {
        // given
        when(
                workspaceDetailQuery.find(
                        1L,
                        10L
                )
        ).thenReturn(Optional.empty());

        // when
        ThrowingCallable action = () -> service.findDetail(
                1L,
                10L
        );

        // then
        assertError(
                action,
                WorkspaceErrorCode.WORKSPACE_NOT_FOUND
        );
    }

    @Test
    @DisplayName("현재 멤버십이 없으면 워크스페이스 정보를 조회할 수 없다")
    void findDetail_failure_accessDenied() {
        // given
        when(
                workspaceDetailQuery.find(
                        1L,
                        10L
                )
        ).thenReturn(
                Optional.of(
                        new WorkspaceDetailResult(
                                "Knot 팀",
                                null,
                                1L
                        )
                )
        );

        // when
        ThrowingCallable action = () -> service.findDetail(
                1L,
                10L
        );

        // then
        assertError(
                action,
                WorkspaceErrorCode.WORKSPACE_ACCESS_DENIED
        );
    }

    @Test
    @DisplayName("인증된 멤버가 속한 워크스페이스를 최근 참여 순서로 조회한다")
    void findAllByMemberId_success() {
        // given
        Workspace recentWorkspace = workspace(
                2L,
                "최근 팀"
        );
        Workspace previousWorkspace = workspace(
                1L,
                "이전 팀"
        );
        when(workspaceRepository.findAllByMemberId(10L)).thenReturn(
                List.of(
                        recentWorkspace,
                        previousWorkspace
                )
        );
        when(workspaceMemberRepository.findLastViewedByMemberId(10L)).thenReturn(
                Optional.of(
                        workspaceMember(
                                2L,
                                10L
                        )
                )
        );

        // when
        WorkspaceListResult result = service.findAllByMemberId(10L);

        // then
        assertThat(result.lastViewedWorkspaceId()).isEqualTo(2L);
        assertThat(result.workspaces()).extracting(
                workspace -> workspace.id(),
                workspace -> workspace.name()
        )
                .containsExactly(
                        tuple(
                                2L,
                                "최근 팀"
                        ),
                        tuple(
                                1L,
                                "이전 팀"
                        )
                );
    }

    @Test
    @DisplayName("소속 워크스페이스가 없으면 빈 목록을 반환한다")
    void findAllByMemberId_success_emptyMemberships() {
        // given
        when(workspaceRepository.findAllByMemberId(10L)).thenReturn(List.of());
        when(workspaceMemberRepository.findLastViewedByMemberId(10L)).thenReturn(Optional.empty());

        // when
        WorkspaceListResult result = service.findAllByMemberId(10L);

        // then
        assertThat(result.lastViewedWorkspaceId()).isNull();
        assertThat(result.workspaces()).isEmpty();
    }

    @Test
    @DisplayName("마지막 조회 멤버십이 목록에 없으면 포인터를 노출하지 않는다")
    void findAllByMemberId_success_filtersStaleLastViewedWorkspace() {
        // given
        Workspace currentWorkspace = workspace(
                1L,
                "현재 팀"
        );
        when(workspaceRepository.findAllByMemberId(10L)).thenReturn(List.of(currentWorkspace));
        when(workspaceMemberRepository.findLastViewedByMemberId(10L)).thenReturn(
                Optional.of(
                        workspaceMember(
                                2L,
                                10L
                        )
                )
        );

        // when
        WorkspaceListResult result = service.findAllByMemberId(10L);

        // then
        assertThat(result.lastViewedWorkspaceId()).isNull();
        assertThat(result.workspaces()).extracting(workspace -> workspace.id())
                .containsExactly(1L);
    }

    @DisplayName("기존 마지막 조회 상태를 해제해 반영한 뒤 요청한 멤버십을 마지막 조회 상태로 저장한다")
    @Test
    void updateLastViewed_success() {
        // given
        WorkspaceMember previous = workspaceMember(
                1L,
                10L
        );
        previous.markLastViewed();
        WorkspaceMember target = workspaceMember(
                2L,
                10L
        );
        List<WorkspaceMember> workspaceMembers = List.of(
                previous,
                target
        );
        when(workspaceMemberRepository.findAllByMemberIdForUpdate(10L)).thenReturn(workspaceMembers);

        // when
        service.updateLastViewed(
                10L,
                2L
        );

        // then
        assertThat(previous.isLastViewed()).isFalse();
        assertThat(target.isLastViewed()).isTrue();
        InOrder inOrder = inOrder(workspaceMemberRepository);
        inOrder.verify(workspaceMemberRepository)
                .saveAll(workspaceMembers);
        inOrder.verify(workspaceMemberRepository)
                .flush();
        inOrder.verify(workspaceMemberRepository)
                .save(target);
    }

    @DisplayName("이미 마지막 조회인 워크스페이스를 다시 설정해도 그 멤버십만 마지막 조회 상태로 남는다")
    @Test
    void updateLastViewed_success_alreadyLastViewed() {
        // given
        WorkspaceMember target = workspaceMember(
                2L,
                10L
        );
        target.markLastViewed();
        when(workspaceMemberRepository.findAllByMemberIdForUpdate(10L)).thenReturn(List.of(target));

        // when
        service.updateLastViewed(
                10L,
                2L
        );

        // then
        assertThat(target.isLastViewed()).isTrue();
        verify(workspaceMemberRepository).save(target);
    }

    @DisplayName("요청한 워크스페이스의 활성 멤버십이 없으면 존재 여부를 숨긴 404 오류를 반환한다")
    @Test
    void updateLastViewed_failure_workspaceNotFound() {
        // given
        when(workspaceMemberRepository.findAllByMemberIdForUpdate(10L)).thenReturn(
                List.of(
                        workspaceMember(
                                1L,
                                10L
                        )
                )
        );

        // when
        ThrowingCallable action = () -> service.updateLastViewed(
                10L,
                2L
        );

        // then
        assertError(
                action,
                WorkspaceErrorCode.WORKSPACE_NOT_FOUND
        );
        verify(
                workspaceMemberRepository,
                never()
        ).saveAll(anyList());
        verify(
                workspaceMemberRepository,
                never()
        ).flush();
    }

    private Workspace workspace(
            long workspaceId,
            String name
    ) {
        Workspace workspace = mock(Workspace.class);
        when(workspace.getId()).thenReturn(workspaceId);
        when(workspace.getName()).thenReturn(name);
        return workspace;
    }

    private WorkspaceMember workspaceMember(
            long workspaceId,
            long memberId
    ) {
        return WorkspaceMember.create(
                workspaceId,
                memberId,
                WorkspaceMemberRole.MEMBER,
                CREATED_AT
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
