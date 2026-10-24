package com.knot.backend.document.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verifyNoInteractions;

import com.knot.backend.document.application.dto.result.DocumentTopicOperationSnapshot;
import com.knot.backend.document.application.dto.result.DocumentTopicOperationResult;
import com.knot.backend.document.domain.model.DocumentTopicOperation;
import com.knot.backend.document.domain.model.DocumentTopicOperationKind;
import com.knot.backend.document.domain.model.DocumentTopicOperationStatus;
import com.knot.backend.workspace.domain.WorkspaceException;
import com.knot.backend.workspace.domain.model.Workspace;
import com.knot.backend.workspace.domain.model.WorkspaceMemberRole;
import com.knot.backend.workspace.domain.repository.WorkspaceRepository;
import com.knot.backend.workspace.domain.repository.WorkspaceMemberRepository;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;

@ExtendWith(MockitoExtension.class)
class DocumentTopicStatusServiceTest {

    @Mock
    private WorkspaceRepository workspaces;
    @Mock
    private WorkspaceMemberRepository members;
    @Mock
    private DocumentTopicStatusQuery query;
    private DocumentTopicStatusService service;

    @BeforeEach
    void setUp() {
        service = new DocumentTopicStatusService(
                workspaces,
                members,
                query
        );
    }

    @Test
    @DisplayName("OWNER는 작업 없는 상태를 조회한다")
    void find_success_empty() {
        allowOwner();
        assertThat(
                service.find(
                        1,
                        2
                )
                        .operation()
        ).isNull();
        assertThat(
                service.find(
                        1,
                        2
                )
                        .canRestore()
        ).isFalse();
    }

    @Test
    @DisplayName("권한 검증 실패 시 작업 정보를 읽지 않는다")
    void find_failure_noAccess() {
        when(workspaces.findById(1L)).thenReturn(Optional.of(mock(Workspace.class)));
        assertThatThrownBy(
                () -> service.find(
                        1,
                        2
                )
        ).isInstanceOf(WorkspaceException.class);
        verifyNoInteractions(query);
    }

    @Test
    @DisplayName("조회 저장소 오류를 정상 빈 상태로 숨기지 않는다")
    void find_failure_storage() {
        allowOwner();
        when(query.findOperation(1)).thenThrow(new DataAccessResourceFailureException("database unavailable"));
        assertThatThrownBy(
                () -> service.find(
                        1,
                        2
                )
        ).isInstanceOf(DataAccessResourceFailureException.class);
    }

    @Test
    @DisplayName("무변경 성공은 변경 수 0을 반환한다")
    void find_success_zeroChanges() {
        allowOwner();
        when(query.findOperation(1)).thenReturn(
                Optional.of(
                        new DocumentTopicOperationSnapshot(
                                1L,
                                DocumentTopicOperationKind.REORGANIZE,
                                DocumentTopicOperationStatus.SUCCEEDED,
                                0,
                                0,
                                0,
                                0,
                                Instant.EPOCH,
                                null,
                                Instant.EPOCH,
                                null
                        )
                )
        );
        assertThat(
                service.find(
                        1,
                        2
                )
                        .operation()
                        .changedDocumentCount()
        ).isZero();
    }

    private void allowOwner() {
        when(workspaces.findById(1L)).thenReturn(Optional.of(mock(Workspace.class)));
        when(
                members.existsByWorkspaceIdAndMemberId(
                        1L,
                        2L
                )
        ).thenReturn(true);
        when(
                members.existsByWorkspaceIdAndMemberIdAndRole(
                        1L,
                        2L,
                        WorkspaceMemberRole.OWNER
                )
        ).thenReturn(true);
    }

    @Test
    @DisplayName("같은 요청의 시작 응답도 재시도 대기를 RUNNING으로 반환한다")
    void replay_success_reportedStatus() {
        DocumentTopicOperation operation = DocumentTopicOperation.reorganize(
                1,
                2,
                UUID.randomUUID(),
                Instant.EPOCH
        );
        UUID token = operation.claim(Instant.EPOCH);
        operation.retry(
                token,
                Instant.EPOCH.plusSeconds(1),
                "TIMEOUT"
        );
        DocumentTopicOperationResult result = DocumentTopicOperationResult.from(
                operation,
                true
        );
        assertThat(result.status()).isEqualTo(DocumentTopicOperationStatus.RUNNING);
    }
}
