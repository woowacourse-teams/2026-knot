package com.knot.backend.document.application;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.knot.backend.document.domain.repository.DocumentTopicOperationRepository;
import com.knot.backend.workspace.domain.WorkspaceException;
import com.knot.backend.workspace.domain.model.Workspace;
import com.knot.backend.workspace.domain.model.WorkspaceMemberRole;
import com.knot.backend.workspace.domain.repository.WorkspaceMemberRepository;
import com.knot.backend.workspace.domain.repository.WorkspaceRepository;
import java.time.Clock;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

class DocumentTopicCancellationServiceTest {

    private final WorkspaceRepository workspaces = mock(WorkspaceRepository.class);
    private final WorkspaceMemberRepository members = mock(WorkspaceMemberRepository.class);
    private final DocumentTopicOperationRepository operations = mock(DocumentTopicOperationRepository.class);
    private final DocumentTopicStore store = mock(DocumentTopicStore.class);
    private final DocumentTopicCancellationService service = new DocumentTopicCancellationService(
            workspaces,
            members,
            operations,
            store,
            Clock.systemUTC()
    );

    @Test
    @DisplayName("Workspace가 없으면 작업 정보를 조회하지 않는다")
    void request_failure_workspaceMissing() {
        // given
        when(workspaces.findByIdForUpdate(12L)).thenReturn(Optional.empty());
        // when & then
        assertThatThrownBy(
                () -> service.request(
                        12,
                        1,
                        30
                )
        ).isInstanceOf(WorkspaceException.class);
        verifyNoInteractions(
                members,
                operations,
                store
        );
    }

    @Test
    @DisplayName("현재 OWNER가 아니면 작업 존재 여부를 노출하지 않는다")
    void request_failure_notOwner() {
        // given
        Workspace workspace = mock(Workspace.class);
        when(workspace.getId()).thenReturn(12L);
        when(workspaces.findByIdForUpdate(12L)).thenReturn(Optional.of(workspace));
        when(
                members.existsByWorkspaceIdAndMemberId(
                        12L,
                        1L
                )
        ).thenReturn(true);
        when(
                members.existsByWorkspaceIdAndMemberIdAndRole(
                        12L,
                        1L,
                        WorkspaceMemberRole.OWNER
                )
        ).thenReturn(false);
        // when & then
        assertThatThrownBy(
                () -> service.request(
                        12,
                        1,
                        30
                )
        ).isInstanceOf(WorkspaceException.class);
        verifyNoInteractions(
                operations,
                store
        );
    }

    @Test
    @DisplayName("DB 오류를 취소 완료로 숨기지 않는다")
    void request_failure_storage() {
        // given
        when(workspaces.findByIdForUpdate(12L)).thenThrow(new DataAccessResourceFailureException("unavailable"));
        // when & then
        assertThatThrownBy(
                () -> service.request(
                        12,
                        1,
                        30
                )
        ).isInstanceOf(DataAccessResourceFailureException.class);
        verifyNoInteractions(
                operations,
                store
        );
    }
}
