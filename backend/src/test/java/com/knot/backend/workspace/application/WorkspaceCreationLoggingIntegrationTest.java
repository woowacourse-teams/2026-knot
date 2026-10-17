package com.knot.backend.workspace.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import ch.qos.logback.classic.spi.ILoggingEvent;
import com.knot.backend.testsupport.LogCapture;
import com.knot.backend.testsupport.TestApplicationProperties;
import com.knot.backend.testsupport.TestcontainersConfiguration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestConstructor;
import org.springframework.transaction.support.TransactionTemplate;

@Tag("integration")
@Import(TestcontainersConfiguration.class)
@TestApplicationProperties
@SpringBootTest
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class WorkspaceCreationLoggingIntegrationTest {
    private final WorkspaceService service;
    private final JdbcClient jdbc;
    private final TransactionTemplate transaction;

    WorkspaceCreationLoggingIntegrationTest(
            WorkspaceService service,
            JdbcClient jdbc,
            TransactionTemplate transaction
    ) {
        this.service = service;
        this.jdbc = jdbc;
        this.transaction = transaction;
    }

    @BeforeEach
    void clear() {
        jdbc.sql("TRUNCATE TABLE workspace_members, workspaces, oauth_identities, members RESTART IDENTITY CASCADE")
                .update();
    }

    @Test
    @DisplayName("실제 OWNER 저장 실패는 생성 성공을 기록하지 않고 Workspace도 롤백한다")
    void membershipFailureRollsBackWorkspaceAndSuccessLog() {
        // given
        try (LogCapture logs = new LogCapture(WorkspaceLog.class)) {
            // when
            Throwable failure = catchThrowable(
                    () -> service.create(
                            Long.MAX_VALUE,
                            "실패 팀"
                    )
            );

            // then
            assertThat(failure).isNotNull();
            assertThat(countWorkspaces()).isZero();
            assertThat(terminal(logs)).containsEntry(
                    "outcome",
                    "failed"
            )
                    .containsEntry(
                            "failedStage",
                            "save_owner_membership"
                    );
            assertThat(logs.events()).noneMatch(event -> "created".equals(fields(event).get("outcome")));
        }
    }

    @Test
    @DisplayName("생성 본문이 끝나도 바깥 트랜잭션 롤백은 created 대신 완료 경계 실패다")
    void outerRollbackDoesNotClaimCommitFailureOrCreated() {
        // given
        long memberId = jdbc.sql("INSERT INTO members (nickname) VALUES ('생성자') RETURNING id")
                .query(Long.class)
                .single();
        IllegalStateException expected = new IllegalStateException("외부 롤백");
        try (LogCapture logs = new LogCapture(WorkspaceLog.class)) {
            // when
            Throwable failure = catchThrowable(() -> transaction.executeWithoutResult(status -> {
                service.create(
                        memberId,
                        "외부 팀"
                );
                assertThat(logs.events()).noneMatch(event -> "created".equals(fields(event).get("outcome")));
                throw expected;
            }));

            // then
            assertThat(failure).isSameAs(expected);
            assertThat(countWorkspaces()).isZero();
            assertThat(terminal(logs)).containsEntry(
                    "outcome",
                    "failed"
            )
                    .containsEntry(
                            "failedStage",
                            "transaction_completion"
                    )
                    .containsEntry(
                            "errorCode",
                            "TRANSACTION_ROLLED_BACK"
                    );
        }
    }

    private long countWorkspaces() {
        return jdbc.sql("SELECT count(*) FROM workspaces")
                .query(Long.class)
                .single();
    }

    private Map<String, Object> terminal(LogCapture logs) {
        return fields(
                logs.events()
                        .getLast()
        );
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
