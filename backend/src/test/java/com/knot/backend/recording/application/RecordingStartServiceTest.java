package com.knot.backend.recording.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.knot.backend.member.domain.Member;
import com.knot.backend.member.domain.MemberRepository;
import com.knot.backend.recording.application.dto.command.RecordingStartCommand;
import com.knot.backend.recording.application.dto.result.RecordingStartResult;
import com.knot.backend.recording.domain.RecordingEndReason;
import com.knot.backend.recording.domain.RecordingException;
import com.knot.backend.recording.domain.RecordingSession;
import com.knot.backend.recording.domain.RecordingSessionRepository;
import com.knot.backend.recording.domain.RecordingStatus;
import com.knot.backend.workspace.domain.Workspace;
import com.knot.backend.workspace.domain.WorkspaceErrorCode;
import com.knot.backend.workspace.domain.WorkspaceException;
import com.knot.backend.workspace.domain.WorkspaceMemberRepository;
import com.knot.backend.workspace.domain.WorkspaceRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class RecordingStartServiceTest {
    private static final Instant NOW = Instant.parse("2026-10-01T00:00:00Z");
    private static final long WORKSPACE_ID = 10L;
    private static final long MEMBER_ID = 1L;
    private static final String HASH = "a".repeat(64);

    private WorkspaceRepository workspaceRepository;
    private WorkspaceMemberRepository workspaceMemberRepository;
    private MemberRepository memberRepository;
    private RecordingSessionRepository recordingSessionRepository;
    private RecordingControlTokenHasher hasher;
    private RecordingStartService service;
    private RecordingStartCommand command;

    @BeforeEach
    void setUp() {
        workspaceRepository = mock(WorkspaceRepository.class);
        workspaceMemberRepository = mock(WorkspaceMemberRepository.class);
        memberRepository = mock(MemberRepository.class);
        recordingSessionRepository = mock(RecordingSessionRepository.class);
        hasher = mock(RecordingControlTokenHasher.class);
        service = new RecordingStartService(
                new RecordingAccess(
                        workspaceRepository,
                        workspaceMemberRepository,
                        recordingSessionRepository,
                        hasher
                ),
                memberRepository,
                recordingSessionRepository,
                hasher,
                Clock.fixed(
                        NOW,
                        ZoneOffset.UTC
                )
        );
        command = new RecordingStartCommand(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "A".repeat(43)
        );
    }

    @Test
    @DisplayName("워크스페이스 소속을 확인하고 본인 잠금 아래 서버 시각으로 녹음을 시작한다")
    void start_success_newSession() {
        // given
        prepareAccess();
        RecordingSession saved = mock(RecordingSession.class);
        when(saved.getId()).thenReturn(7L);
        when(saved.getStatus()).thenReturn(RecordingStatus.RECORDING);
        when(saved.getStartedAt()).thenReturn(NOW);
        when(recordingSessionRepository.save(any(RecordingSession.class))).thenReturn(saved);

        // when
        RecordingStartResult result = service.start(
                WORKSPACE_ID,
                MEMBER_ID,
                command
        );

        // then
        assertThat(result.created()).isTrue();
        assertThat(result.recordingId()).isEqualTo(7L);
        ArgumentCaptor<RecordingSession> created = ArgumentCaptor.forClass(RecordingSession.class);
        verify(recordingSessionRepository).save(created.capture());
        assertThat(
                created.getValue()
                        .getStartedAt()
        ).isEqualTo(NOW);
        assertThat(
                created.getValue()
                        .getMemberId()
        ).isEqualTo(MEMBER_ID);
        assertThat(
                catchThrowable(
                        () -> created.getValue()
                                .validateStartRetry(
                                        WORKSPACE_ID,
                                        command.tabId(),
                                        HASH
                                )
                )
        ).isNull();
        org.mockito.InOrder order = org.mockito.Mockito.inOrder(
                workspaceRepository,
                workspaceMemberRepository,
                memberRepository,
                recordingSessionRepository
        );
        order.verify(workspaceRepository)
                .findByIdForUpdate(WORKSPACE_ID);
        order.verify(workspaceMemberRepository)
                .existsByWorkspaceIdAndMemberId(
                        WORKSPACE_ID,
                        MEMBER_ID
                );
        order.verify(memberRepository)
                .findByIdForUpdate(MEMBER_ID);
        order.verify(recordingSessionRepository)
                .findByMemberIdAndRequestIdForUpdate(
                        MEMBER_ID,
                        command.requestId()
                );
        order.verify(recordingSessionRepository)
                .findAllActiveByMemberIdForUpdate(MEMBER_ID);
        order.verify(recordingSessionRepository)
                .save(any(RecordingSession.class));
    }

    @Test
    @DisplayName("워크스페이스 ID가 양수가 아니면 조회 없이 거부한다")
    void start_failure_invalidWorkspaceId() {
        // given
        long invalidWorkspaceId = 0L;

        // when
        Throwable failure = catchThrowable(
                () -> service.start(
                        invalidWorkspaceId,
                        MEMBER_ID,
                        command
                )
        );

        // then
        assertThat(failure).isInstanceOfSatisfying(
                WorkspaceException.class,
                exception -> assertThat(exception.getErrorCode()).isEqualTo(WorkspaceErrorCode.INVALID_WORKSPACE_ID)
        );
        verifyNoInteractions(
                workspaceRepository,
                memberRepository,
                recordingSessionRepository,
                hasher
        );
    }

    @Test
    @DisplayName("워크스페이스가 없으면 본인 잠금과 녹음 저장 없이 거부한다")
    void start_failure_missingWorkspace() {
        // given

        // when
        Throwable failure = catchThrowable(
                () -> service.start(
                        WORKSPACE_ID,
                        MEMBER_ID,
                        command
                )
        );

        // then
        assertThat(failure).isInstanceOf(WorkspaceException.class)
                .hasMessage("워크스페이스를 찾을 수 없습니다");
        verifyNoInteractions(
                memberRepository,
                recordingSessionRepository,
                hasher
        );
    }

    @Test
    @DisplayName("워크스페이스 멤버가 아니면 녹음 키와 제어 증명을 조회하지 않는다")
    void start_failure_nonMember() {
        // given
        when(workspaceRepository.findByIdForUpdate(WORKSPACE_ID)).thenReturn(Optional.of(mock(Workspace.class)));

        // when
        Throwable failure = catchThrowable(
                () -> service.start(
                        WORKSPACE_ID,
                        MEMBER_ID,
                        command
                )
        );

        // then
        assertThat(failure).isInstanceOf(WorkspaceException.class)
                .hasMessage("워크스페이스에 접근할 수 없습니다");
        verifyNoInteractions(
                memberRepository,
                recordingSessionRepository,
                hasher
        );
    }

    @Test
    @DisplayName("같은 시작 요청은 종료된 세션도 현재 상태 그대로 반환하고 새로 저장하지 않는다")
    void start_success_replaysEndedSession() {
        // given
        prepareAccess();
        RecordingSession existing = mock(RecordingSession.class);
        when(existing.getId()).thenReturn(7L);
        when(existing.getStatus()).thenReturn(RecordingStatus.ENDED);
        when(existing.getStartedAt()).thenReturn(NOW);
        when(
                recordingSessionRepository.findByMemberIdAndRequestIdForUpdate(
                        MEMBER_ID,
                        command.requestId()
                )
        ).thenReturn(Optional.of(existing));

        // when
        RecordingStartResult result = service.start(
                WORKSPACE_ID,
                MEMBER_ID,
                command
        );

        // then
        assertThat(result.created()).isFalse();
        assertThat(result.status()).isEqualTo(RecordingStatus.ENDED);
        assertThat(result.recordingId()).isEqualTo(7L);
        org.mockito.Mockito.verify(
                recordingSessionRepository,
                org.mockito.Mockito.never()
        )
                .save(any(RecordingSession.class));
    }

    @Test
    @DisplayName("다른 요청으로 시작한 활성 녹음이 있으면 새 세션을 생성하지 않는다")
    void start_failure_activeSession() {
        // given
        prepareAccess();
        when(recordingSessionRepository.findAllActiveByMemberIdForUpdate(MEMBER_ID)).thenReturn(
                List.of(
                        RecordingSession.start(
                                WORKSPACE_ID + 1,
                                MEMBER_ID,
                                UUID.randomUUID(),
                                UUID.randomUUID(),
                                HASH,
                                NOW.minusSeconds(29)
                        )
                )
        );

        // when
        Throwable failure = catchThrowable(
                () -> service.start(
                        WORKSPACE_ID,
                        MEMBER_ID,
                        command
                )
        );

        // then
        assertThat(failure).isInstanceOf(RecordingException.class);
        org.mockito.Mockito.verify(
                recordingSessionRepository,
                org.mockito.Mockito.never()
        )
                .save(any(RecordingSession.class));
    }

    @Test
    @DisplayName("다른 Workspace에 남은 본인 녹음의 연결이 끊겼으면 만료로 회수한 뒤 새 녹음을 시작한다")
    void start_success_reclaimsDisconnectedSession() {
        // given
        prepareAccess();
        RecordingSession disconnected = withId(
                RecordingSession.start(
                        WORKSPACE_ID + 1,
                        MEMBER_ID,
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        HASH,
                        NOW.minusSeconds(30)
                ),
                6L
        );
        when(recordingSessionRepository.findAllActiveByMemberIdForUpdate(MEMBER_ID)).thenReturn(List.of(disconnected));
        when(recordingSessionRepository.save(any(RecordingSession.class))).thenAnswer(
                invocation -> invocation.getArgument(0) == disconnected
                        ? disconnected
                        : withId(
                                invocation.getArgument(0),
                                8L
                        )
        );

        // when
        RecordingStartResult result = service.start(
                WORKSPACE_ID,
                MEMBER_ID,
                command
        );

        // then
        assertThat(result.created()).isTrue();
        assertThat(result.status()).isEqualTo(RecordingStatus.RECORDING);
        assertThat(disconnected.getStatus()).isEqualTo(RecordingStatus.ENDED);
        assertThat(disconnected.getEndReason()).isEqualTo(RecordingEndReason.CONNECTION_EXPIRED);
        assertThat(disconnected.getEndedAt()).isEqualTo(NOW);
        verify(recordingSessionRepository).save(disconnected);
    }

    @Test
    @DisplayName("같은 시작 요청을 다시 보냈을 때 연결이 끊긴 녹음이면 만료를 저장하고 종료 상태를 돌려준다")
    void start_success_replayExpiresDisconnectedSession() {
        // given
        prepareAccess();
        RecordingSession existing = withId(
                RecordingSession.start(
                        WORKSPACE_ID,
                        MEMBER_ID,
                        command.requestId(),
                        command.tabId(),
                        HASH,
                        NOW.minusSeconds(300)
                ),
                7L
        );
        when(
                recordingSessionRepository.findByMemberIdAndRequestIdForUpdate(
                        MEMBER_ID,
                        command.requestId()
                )
        ).thenReturn(Optional.of(existing));

        // when
        RecordingStartResult result = service.start(
                WORKSPACE_ID,
                MEMBER_ID,
                command
        );

        // then
        assertThat(result.created()).isFalse();
        assertThat(result.status()).isEqualTo(RecordingStatus.ENDED);
        assertThat(existing.getEndedAt()).isEqualTo(NOW.minusSeconds(270));
        assertThat(existing.getLastSeenAt()).isEqualTo(NOW.minusSeconds(300));
        verify(recordingSessionRepository).save(existing);
    }

    private RecordingSession withId(
            RecordingSession session,
            long id
    ) {
        RecordingSession identified = spy(session);
        doReturn(id).when(identified)
                .getId();
        return identified;
    }

    private void prepareAccess() {
        when(workspaceRepository.findByIdForUpdate(WORKSPACE_ID)).thenReturn(Optional.of(mock(Workspace.class)));
        when(
                workspaceMemberRepository.existsByWorkspaceIdAndMemberId(
                        WORKSPACE_ID,
                        MEMBER_ID
                )
        ).thenReturn(true);
        when(memberRepository.findByIdForUpdate(MEMBER_ID)).thenReturn(Optional.of(mock(Member.class)));
        when(hasher.hash(command.controlToken())).thenReturn(HASH);
    }
}
