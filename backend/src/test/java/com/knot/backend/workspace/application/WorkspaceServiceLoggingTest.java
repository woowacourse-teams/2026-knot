package com.knot.backend.workspace.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import com.knot.backend.testsupport.LogCapture;
import com.knot.backend.workspace.application.dto.result.WorkspaceDetailResult;
import com.knot.backend.workspace.application.dto.result.WorkspaceListResult;
import com.knot.backend.workspace.domain.model.Workspace;
import com.knot.backend.workspace.domain.model.WorkspaceMember;
import com.knot.backend.workspace.domain.model.WorkspaceMemberRole;
import com.knot.backend.workspace.domain.repository.WorkspaceMemberRepository;
import com.knot.backend.workspace.domain.repository.WorkspaceRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.test.util.ReflectionTestUtils;

class WorkspaceServiceLoggingTest {
    private static final Instant NOW = Instant.parse("2026-10-17T00:00:00Z");

    private final WorkspaceRepository workspaces = mock(WorkspaceRepository.class);
    private final WorkspaceMemberRepository members = mock(WorkspaceMemberRepository.class);
    private final WorkspaceDetailQuery details = mock(WorkspaceDetailQuery.class);
    private final WorkspaceService service = new WorkspaceService(
            workspaces,
            members,
            details,
            Clock.fixed(
                    NOW,
                    ZoneOffset.UTC
            )
    );

    @Test
    @DisplayName("생성 로그는 새 ID와 OWNER 저장 단계를 연결하고 이름을 기록하지 않는다")
    void createConnectsGeneratedWorkspaceId() {
        // given
        when(workspaces.save(any(Workspace.class))).thenAnswer(call -> {
            Workspace workspace = call.getArgument(0);
            ReflectionTestUtils.setField(
                    workspace,
                    "id",
                    7L
            );
            return workspace;
        });
        try (LogCapture logs = new LogCapture(WorkspaceLog.class)) {
            // when
            service.create(
                    3L,
                    "비밀회의"
            );

            // then
            assertThat(terminal(logs)).containsEntry(
                    "event",
                    "workspace.create"
            )
                    .containsEntry(
                            "outcome",
                            "created"
                    )
                    .containsEntry(
                            "workspaceId",
                            7L
                    );
            assertThat(preparedStages(logs)).containsExactly(
                    "create_workspace",
                    "save_workspace",
                    "save_owner_membership"
            );
            assertThat(logs.events())
                    .allSatisfy(event -> assertThat(event.getFormattedMessage()).doesNotContain("비밀회의"));
        }
    }

    @Test
    @DisplayName("이름 검증 거절은 저장 실패와 구분하며 입력 원문을 제외한다")
    void createRejectsInvalidNameAtCreationStage() {
        // given
        try (LogCapture logs = new LogCapture(WorkspaceLog.class)) {
            // when
            Throwable failure = catchThrowable(
                    () -> service.create(
                            3L,
                            "PRIVATE_INVALID_NAME!"
                    )
            );

            // then
            assertThat(failure).isNotNull();
            assertThat(terminal(logs)).containsEntry(
                    "outcome",
                    "rejected"
            )
                    .containsEntry(
                            "failedStage",
                            "create_workspace"
                    )
                    .containsEntry(
                            "errorCode",
                            "INVALID_WORKSPACE_NAME"
                    );
            assertThat(preparedStages(logs)).isEmpty();
            assertThat(logs.events()).allSatisfy(
                    event -> assertThat(event.getFormattedMessage()).doesNotContain("PRIVATE_INVALID_NAME")
            );
        }
    }

    @Test
    @DisplayName("상세 조회는 정상 완료와 조회 구간을 기록한다")
    void detailRecordsCompletedRead() {
        // given
        WorkspaceDetailResult detail = new WorkspaceDetailResult(
                "조회 비밀",
                WorkspaceMemberRole.OWNER,
                2L
        );
        when(
                details.find(
                        7L,
                        3L
                )
        ).thenReturn(Optional.of(detail));
        try (LogCapture logs = new LogCapture(WorkspaceLog.class)) {
            // when
            WorkspaceDetailResult result = service.findDetail(
                    7L,
                    3L
            );

            // then
            assertThat(result).isSameAs(detail);
            assertThat(terminal(logs)).containsEntry(
                    "event",
                    "workspace.detail"
            )
                    .containsEntry(
                            "outcome",
                            "completed"
                    )
                    .containsEntry(
                            "workspaceId",
                            7L
                    );
            assertThat(preparedStages(logs)).containsExactly(
                    "validate_workspace_id",
                    "load_detail",
                    "validate_membership"
            );
            assertThat(logs.events())
                    .allSatisfy(event -> assertThat(event.getFormattedMessage()).doesNotContain("조회 비밀"));
        }
    }

    @Test
    @DisplayName("상세 조회 미존재와 권한 거절은 실패 단계를 구분한다")
    void detailRejectsAbsentWorkspace() {
        // given
        when(
                details.find(
                        7L,
                        3L
                )
        ).thenReturn(Optional.empty());
        try (LogCapture logs = new LogCapture(WorkspaceLog.class)) {
            // when
            Throwable failure = catchThrowable(
                    () -> service.findDetail(
                            7L,
                            3L
                    )
            );

            // then
            assertThat(failure).isNotNull();
            assertThat(terminal(logs)).containsEntry(
                    "outcome",
                    "rejected"
            )
                    .containsEntry(
                            "failedStage",
                            "load_detail"
                    )
                    .containsEntry(
                            "errorCode",
                            "WORKSPACE_NOT_FOUND"
                    );
        }
    }

    @Test
    @DisplayName("상세 조회 비멤버는 멤버십 검증에서 거절됐음을 기록한다")
    void detailRejectsNonMember() {
        // given
        when(
                details.find(
                        7L,
                        3L
                )
        ).thenReturn(
                Optional.of(
                        new WorkspaceDetailResult(
                                "팀",
                                null,
                                2L
                        )
                )
        );
        try (LogCapture logs = new LogCapture(WorkspaceLog.class)) {
            // when
            Throwable failure = catchThrowable(
                    () -> service.findDetail(
                            7L,
                            3L
                    )
            );

            // then
            assertThat(failure).isNotNull();
            assertThat(terminal(logs)).containsEntry(
                    "outcome",
                    "rejected"
            )
                    .containsEntry(
                            "failedStage",
                            "validate_membership"
                    )
                    .containsEntry(
                            "errorCode",
                            "WORKSPACE_ACCESS_DENIED"
                    );
        }
    }

    @Test
    @DisplayName("빈 목록은 실패가 아닌 정상 조회 완료다")
    void emptyListIsCompleted() {
        // given
        when(workspaces.findAllByMemberId(3L)).thenReturn(List.of());
        when(members.findLastViewedByMemberId(3L)).thenReturn(Optional.empty());
        try (LogCapture logs = new LogCapture(WorkspaceLog.class)) {
            // when
            WorkspaceListResult result = service.findAllByMemberId(3L);

            // then
            assertThat(result.workspaces()).isEmpty();
            assertThat(terminal(logs)).containsEntry(
                    "event",
                    "workspace.list"
            )
                    .containsEntry(
                            "outcome",
                            "completed"
                    );
            assertThat(preparedStages(logs)).containsExactly(
                    "load_workspaces",
                    "load_last_viewed"
            );
        }
    }

    @Test
    @DisplayName("목록 DB 장애는 ERROR와 실패 단계만 남기고 예외 메시지를 제외한다")
    void listFailureDoesNotExposeExceptionMessage() {
        // given
        IllegalStateException expected = new IllegalStateException("PRIVATE_QUERY_DETAILS");
        when(workspaces.findAllByMemberId(3L)).thenThrow(expected);
        try (LogCapture logs = new LogCapture(WorkspaceLog.class)) {
            // when
            Throwable failure = catchThrowable(() -> service.findAllByMemberId(3L));

            // then
            assertThat(failure).isSameAs(expected);
            assertThat(terminal(logs)).containsEntry(
                    "outcome",
                    "failed"
            )
                    .containsEntry(
                            "failedStage",
                            "load_workspaces"
                    );
            assertThat(
                    logs.events()
                            .getLast()
                            .getLevel()
            ).isEqualTo(Level.ERROR);
            assertThat(logs.events()).allSatisfy(
                    event -> assertThat(event.getFormattedMessage()).doesNotContain("PRIVATE_QUERY_DETAILS")
            );
        }
    }

    @ParameterizedTest
    @CsvSource({"false,changed", "true,replayed"})
    @DisplayName("마지막 선택 갱신과 같은 선택의 재요청을 구분한다")
    void lastViewedDistinguishesReplay(
            boolean alreadyViewed,
            String expectedOutcome
    ) {
        // given
        WorkspaceMember membership = WorkspaceMember.create(
                7L,
                3L,
                WorkspaceMemberRole.MEMBER,
                NOW
        );
        if (alreadyViewed) {
            membership.markLastViewed();
        }
        when(members.findAllByMemberIdForUpdate(3L)).thenReturn(List.of(membership));
        try (LogCapture logs = new LogCapture(WorkspaceLog.class)) {
            // when
            service.updateLastViewed(
                    3L,
                    7L
            );

            // then
            assertThat(terminal(logs)).containsEntry(
                    "event",
                    "workspace.last_viewed"
            )
                    .containsEntry(
                            "outcome",
                            expectedOutcome
                    );
            assertThat(membership.isLastViewed()).isTrue();
        }
    }

    private Map<String, Object> terminal(LogCapture logs) {
        return fields(
                logs.events()
                        .getLast()
        );
    }

    private List<Object> preparedStages(LogCapture logs) {
        return logs.events()
                .stream()
                .map(this::fields)
                .filter(event -> "prepared".equals(event.get("outcome")))
                .map(event -> event.get("stage"))
                .toList();
    }

    private Map<String, Object> fields(ILoggingEvent event) {
        Map<String, Object> fields = new LinkedHashMap<>();
        event.getKeyValuePairs()
                .forEach(
                        pair -> fields.put(
                                pair.key,
                                pair.value
                        )
                );
        return fields;
    }
}
