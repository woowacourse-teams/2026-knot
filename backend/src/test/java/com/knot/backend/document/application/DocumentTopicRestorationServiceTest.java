package com.knot.backend.document.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.knot.backend.document.application.dto.result.DocumentTopicRestorationSource;
import com.knot.backend.document.domain.model.DocumentTopicOperation;
import com.knot.backend.document.domain.repository.DocumentTopicOperationRepository;
import com.knot.backend.workspace.domain.model.Workspace;
import com.knot.backend.workspace.domain.model.WorkspaceMemberRole;
import com.knot.backend.workspace.domain.repository.WorkspaceRepository;
import com.knot.backend.workspace.domain.repository.WorkspaceMemberRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.test.util.ReflectionTestUtils;

class DocumentTopicRestorationServiceTest {
    private static final Instant NOW = Instant.parse("2026-10-24T00:00:00.123456789Z");

    private final WorkspaceRepository workspaces = mock(WorkspaceRepository.class);
    private final WorkspaceMemberRepository members = mock(WorkspaceMemberRepository.class);
    private final DocumentTopicOperationRepository operations = mock(DocumentTopicOperationRepository.class);
    private final DocumentTopicRestorationStore store = mock(DocumentTopicRestorationStore.class);
    private final DocumentTopicRestorationService service = new DocumentTopicRestorationService(
            workspaces,
            members,
            operations,
            store,
            Clock.fixed(
                    NOW,
                    ZoneOffset.UTC
            )
    );
    private final UUID request = UUID.randomUUID();

    @BeforeEach
    void setUp() {
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
        ).thenReturn(true);
        when(
                operations.findByWorkspaceIdAndRequestId(
                        12L,
                        request
                )
        ).thenReturn(Optional.empty());
    }

    @Test
    @DisplayName("성공한 요청의 재전송은 활성 작업·복원 정보 조회보다 먼저 반환한다")
    void start_success_replayBeforeAvailability() {
        // given
        DocumentTopicOperation previous = DocumentTopicOperation.restoration(
                12L,
                1,
                request,
                91,
                2,
                NOW
        );
        ReflectionTestUtils.setField(
                previous,
                "id",
                3L
        );
        when(
                operations.findByWorkspaceIdAndRequestId(
                        12L,
                        request
                )
        ).thenReturn(Optional.of(previous));
        // when
        assertThat(
                service.start(
                        12L,
                        1,
                        request,
                        91
                )
                        .replayed()
        ).isTrue();
        // then
        verify(
                operations,
                never()
        ).hasActive(12L);
        verifyNoInteractions(store);
    }

    @Test
    @DisplayName("접수는 권한·중복·활성 작업·현재 버전 확인 후 고정 대상을 저장한다")
    void start_success_capturesAfterValidation() {
        // given
        when(store.findSourceForUpdate(12L)).thenReturn(
                Optional.of(
                        new DocumentTopicRestorationSource(
                                91,
                                2,
                                1
                        )
                )
        );
        when(operations.save(any())).thenAnswer(invocation -> {
            DocumentTopicOperation operation = invocation.getArgument(0);
            ReflectionTestUtils.setField(
                    operation,
                    "id",
                    3L
            );
            return operation;
        });
        when(
                store.capture(
                        3,
                        12L,
                        2
                )
        ).thenReturn(1);
        // when
        assertThat(
                service.start(
                        12L,
                        1,
                        request,
                        91
                )
                        .createdAt()
        ).isEqualTo(Instant.parse("2026-10-24T00:00:00.123456Z"));
        // then
        InOrder order = inOrder(
                members,
                operations,
                store
        );
        order.verify(members)
                .existsByWorkspaceIdAndMemberId(
                        12L,
                        1L
                );
        order.verify(members)
                .existsByWorkspaceIdAndMemberIdAndRole(
                        12L,
                        1L,
                        WorkspaceMemberRole.OWNER
                );
        order.verify(operations)
                .findByWorkspaceIdAndRequestId(
                        12L,
                        request
                );
        order.verify(operations)
                .hasActive(12L);
        order.verify(store)
                .findSourceForUpdate(12L);
        order.verify(operations)
                .save(any());
        order.verify(store)
                .capture(
                        3,
                        12L,
                        2
                );
    }

    @Test
    @DisplayName("권한 없는 멤버의 재전송도 기존 작업이나 복원 정보를 조회하지 않는다")
    void start_failure_ownerRequired() {
        // given
        when(
                members.existsByWorkspaceIdAndMemberIdAndRole(
                        12L,
                        1L,
                        WorkspaceMemberRole.OWNER
                )
        ).thenReturn(false);
        // when & then
        assertThatThrownBy(
                () -> service.start(
                        12L,
                        1,
                        request,
                        91
                )
        ).hasMessageContaining("OWNER");
        verifyNoInteractions(
                operations,
                store
        );
    }

    @Test
    @DisplayName("현재 성공 원본의 변경 수와 고정 대상 수가 다르면 저장 자료 오류다")
    void start_failure_targetCountMismatch() {
        // given
        when(store.findSourceForUpdate(12L)).thenReturn(
                Optional.of(
                        new DocumentTopicRestorationSource(
                                91,
                                2,
                                2
                        )
                )
        );
        when(operations.save(any())).thenAnswer(invocation -> {
            DocumentTopicOperation operation = invocation.getArgument(0);
            ReflectionTestUtils.setField(
                    operation,
                    "id",
                    3L
            );
            return operation;
        });
        when(
                store.capture(
                        3,
                        12L,
                        2
                )
        ).thenReturn(1);
        // when & then
        assertThatThrownBy(
                () -> service.start(
                        12L,
                        1,
                        request,
                        91
                )
        ).hasMessageContaining("복원 자료");
    }
}
