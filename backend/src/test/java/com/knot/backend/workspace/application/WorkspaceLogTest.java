package com.knot.backend.workspace.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.Appender;
import com.knot.backend.global.exception.ErrorCategory;
import com.knot.backend.global.exception.ErrorCode;
import com.knot.backend.global.exception.ProjectException;
import com.knot.backend.testsupport.LogCapture;
import com.knot.backend.workspace.domain.WorkspaceErrorCode;
import com.knot.backend.workspace.domain.WorkspaceException;
import java.io.Serial;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class WorkspaceLogTest {
    private final Connection connection = mock(Connection.class);
    private TransactionTemplate transaction;

    @BeforeEach
    void setUp() throws SQLException {
        DataSource dataSource = mock(DataSource.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.getAutoCommit()).thenReturn(true);
        transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    }

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    @DisplayName("단계 준비는 즉시 남기고 생성 성공과 전체 시간은 DB 커밋 뒤 확정한다")
    void commit_recordsPreparedBeforeDurableSuccess() throws SQLException {
        // given
        doAnswer(invocation -> {
            TimeUnit.MILLISECONDS.sleep(20);
            return null;
        }).when(connection)
                .commit();
        try (LogCapture logs = new LogCapture(WorkspaceLog.class)) {
            // when
            transaction.executeWithoutResult(status -> {
                WorkspaceLog.Scope operation = WorkspaceLog.begin(
                        "workspace.create",
                        null
                );
                operation.call(() -> {
                    operation.step(
                            "workspace_save",
                            () -> 7L
                    );
                    operation.workspace(7)
                            .outcome("created");
                    return 7L;
                });
                assertThat(logs.events()).hasSize(3);
                assertThat(
                        fields(
                                logs.events()
                                        .getLast()
                        )
                ).containsEntry(
                        "outcome",
                        "prepared"
                );
            });
            // then
            List<ILoggingEvent> events = logs.events();
            assertThat(events).hasSize(4);
            Map<String, Object> operation = fields(events.getLast());
            assertThat(operation).containsEntry(
                    "workspaceId",
                    7L
            )
                    .containsEntry(
                            "outcome",
                            "created"
                    )
                    .containsEntry(
                            "stage",
                            "operation"
                    );
            assertThat((long) operation.get("elapsedMs")).isGreaterThanOrEqualTo(20);
            assertThat((long) operation.get("elapsedMs")).isGreaterThan((long) fields(events.get(2)).get("elapsedMs"));
            verify(connection).commit();
        }
    }

    @Test
    @DisplayName("단계의 업무 거절은 롤백 뒤에도 해당 단계와 안전한 오류 코드로 남긴다")
    void rollback_preservesRejectedStage() throws SQLException {
        // given
        WorkspaceException failure = new WorkspaceException(WorkspaceErrorCode.WORKSPACE_ACCESS_DENIED);
        try (LogCapture logs = new LogCapture(WorkspaceLog.class)) {
            // when
            assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
                WorkspaceLog.Scope operation = WorkspaceLog.begin(
                        "workspace.leave",
                        7L
                );
                operation.run(
                        () -> operation.runStep(
                                "membership_check",
                                () -> {
                                    throw failure;
                                }
                        )
                );
            })).isSameAs(failure);
            // then
            ILoggingEvent terminal = logs.events()
                    .getLast();
            assertThat(fields(terminal)).containsEntry(
                    "outcome",
                    "rejected"
            )
                    .containsEntry(
                            "failedStage",
                            "membership_check"
                    )
                    .containsEntry(
                            "errorCode",
                            "WORKSPACE_ACCESS_DENIED"
                    )
                    .doesNotContainKey("diagnostic");
            assertThat(terminal.getLevel()).isEqualTo(Level.INFO);
            verify(connection).rollback();
        }
    }

    @Test
    @DisplayName("내부 처리가 준비되어도 바깥 트랜잭션 롤백이면 폐기 성공을 남기지 않는다")
    void outerRollback_revertsRootAndDiscard() throws SQLException {
        // given
        try (LogCapture logs = new LogCapture(WorkspaceLog.class)) {
            // when
            transaction.executeWithoutResult(outer -> {
                WorkspaceLog.begin(
                        "workspace.leave",
                        7L
                )
                        .outcome("left")
                        .run(
                                () -> transaction.executeWithoutResult(
                                        inner -> WorkspaceLog.discard(
                                                7,
                                                11L,
                                                "workspace_left"
                                        )
                                                .run(() -> {
                                                })
                                )
                        );
                outer.setRollbackOnly();
            });
            // then
            List<ILoggingEvent> terminal = logs.events()
                    .stream()
                    .filter(event -> !"started".equals(fields(event).get("outcome")))
                    .toList();
            assertThat(terminal).hasSize(2);
            assertThat(fields(terminal.getFirst())).containsEntry(
                    "outcome",
                    "failed"
            )
                    .containsEntry(
                            "errorCode",
                            "TRANSACTION_ROLLED_BACK"
                    )
                    .containsEntry(
                            "failedStage",
                            "transaction_completion"
                    );
            assertThat(fields(terminal.getLast())).containsEntry(
                    "outcome",
                    "rolled_back"
            )
                    .containsEntry(
                            "recordingId",
                            11L
                    )
                    .containsEntry(
                            "reason",
                            "workspace_left"
                    );
            assertThat(
                    terminal.getLast()
                            .getLevel()
            ).isEqualTo(Level.INFO);
            verify(connection).rollback();
        }
    }

    @Test
    @DisplayName("커밋 결과를 확인할 수 없으면 조회 결과도 성공 대신 미확인으로 남긴다")
    void commitFailure_recordsUnknown() throws SQLException {
        // given
        doThrow(new SQLException("private-db-body")).when(connection)
                .commit();
        try (LogCapture logs = new LogCapture(WorkspaceLog.class)) {
            // when
            assertThatThrownBy(
                    () -> transaction.executeWithoutResult(
                            status -> WorkspaceLog.begin(
                                    "workspace.list",
                                    null
                            )
                                    .outcome("listed")
                                    .call(List::of)
                    )
            ).isInstanceOf(TransactionSystemException.class);
            // then
            ILoggingEvent terminal = logs.events()
                    .getLast();
            assertThat(fields(terminal)).containsEntry(
                    "outcome",
                    "unconfirmed"
            )
                    .containsEntry(
                            "errorCode",
                            "TRANSACTION_UNCONFIRMED"
                    )
                    .containsEntry(
                            "failedStage",
                            "transaction_completion"
                    );
            assertThat(terminal.getLevel()).isEqualTo(Level.WARN);
            assertThat(terminal.getFormattedMessage()).doesNotContain("private-db-body");
        }
    }

    @Test
    @DisplayName("업무 실패 뒤 롤백 결과도 미확인이면 실제 실패 단계를 보존한다")
    void rollbackFailure_preservesKnownFailedStage() throws SQLException {
        // given
        doThrow(new SQLException("private-rollback-message")).when(connection)
                .rollback();
        WorkspaceException failure = new WorkspaceException(WorkspaceErrorCode.WORKSPACE_ACCESS_DENIED);
        try (LogCapture logs = new LogCapture(WorkspaceLog.class)) {
            // when
            assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
                WorkspaceLog.Scope operation = WorkspaceLog.begin(
                        "workspace.leave",
                        7L
                );
                operation.run(
                        () -> operation.runStep(
                                "membership_check",
                                () -> {
                                    throw failure;
                                }
                        )
                );
            })).isInstanceOf(TransactionSystemException.class);
            // then
            ILoggingEvent terminal = logs.events()
                    .getLast();
            assertThat(fields(terminal)).containsEntry(
                    "outcome",
                    "unconfirmed"
            )
                    .containsEntry(
                            "failedStage",
                            "membership_check"
                    )
                    .containsEntry(
                            "errorCode",
                            "TRANSACTION_UNCONFIRMED"
                    );
            assertThat(terminal.getLevel()).isEqualTo(Level.WARN);
            assertThat(terminal.getFormattedMessage()).doesNotContain("private-rollback-message");
        }
    }

    @Test
    @DisplayName("녹음 ID가 없어도 업무를 실행하고 폐기 롤백과 이유를 기록한다")
    void discard_withoutRecordingIdDoesNotChangeAction() {
        // given
        try (LogCapture logs = new LogCapture(WorkspaceLog.class)) {
            // when
            transaction.executeWithoutResult(status -> {
                String result = WorkspaceLog.discard(
                        7,
                        null,
                        "WORKSPACE_MEMBER_LEFT"
                )
                        .call(() -> "executed");
                assertThat(result).isEqualTo("executed");
                status.setRollbackOnly();
            });
            // then
            assertThat(
                    fields(
                            logs.events()
                                    .getLast()
                    )
            ).containsEntry(
                    "recordingId",
                    null
            )
                    .containsEntry(
                            "reason",
                            "WORKSPACE_MEMBER_LEFT"
                    )
                    .containsEntry(
                            "outcome",
                            "rolled_back"
                    );
        }
    }

    @Test
    @DisplayName("폐기 시작부터 녹음과 탈퇴 이유를 남기며 캡처한 요청 ID와 JSON 필드는 일치한다")
    void discard_capturesCorrelationBeforeFirstLog() {
        // given
        MDC.put(
                "requestId",
                "request-before"
        );
        try (LogCapture logs = new LogCapture(WorkspaceLog.class)) {
            // when
            WorkspaceLog.Scope discard = WorkspaceLog.discard(
                    7,
                    11L,
                    "ownership_transferred"
            );
            MDC.put(
                    "requestId",
                    "request-after"
            );
            String result = discard.call(() -> "private-result-body");
            // then
            assertThat(result).isEqualTo("private-result-body");
            assertThat(logs.events()).hasSize(2)
                    .allSatisfy(event -> {
                        Map<String, Object> values = fields(event);
                        assertThat(values).containsEntry(
                                "requestId",
                                "request-before"
                        )
                                .containsEntry(
                                        "workspaceId",
                                        7L
                                )
                                .containsEntry(
                                        "recordingId",
                                        11L
                                )
                                .containsEntry(
                                        "reason",
                                        "ownership_transferred"
                                );
                        JsonNode json = JsonMapper.builder()
                                .build()
                                .readTree(event.getFormattedMessage());
                        assertThat(
                                json.get("requestId")
                                        .asString()
                        ).isEqualTo(values.get("requestId"));
                        assertThat(
                                json.get("recordingId")
                                        .asLong()
                        ).isEqualTo((long) values.get("recordingId"));
                        assertThat(
                                json.get("outcome")
                                        .asString()
                        ).isEqualTo(values.get("outcome"));
                        assertThat(event.getFormattedMessage()).doesNotContain("private-result-body");
                    });
            assertThat(
                    fields(
                            logs.events()
                                    .getLast()
                    )
            ).containsEntry(
                    "outcome",
                    "discarded"
            );
        }
    }

    @Test
    @DisplayName("4xx 업무 코드여도 기술 원인이 있으면 실패로 분류하고 메시지는 숨긴다")
    void technicalCause_recordsErrorWithoutPrivateMessage() {
        // given
        WorkspaceException failure = new WorkspaceException(
                WorkspaceErrorCode.WORKSPACE_ACCESS_DENIED,
                new SQLException("private-sql-and-token")
        );
        try (LogCapture logs = new LogCapture(WorkspaceLog.class)) {
            // when
            assertThatThrownBy(
                    () -> WorkspaceLog.begin(
                            "workspace.detail",
                            7L
                    )
                            .call(() -> {
                                throw failure;
                            })
            ).isSameAs(failure);
            // then
            ILoggingEvent terminal = logs.events()
                    .getLast();
            assertThat(terminal.getLevel()).isEqualTo(Level.ERROR);
            assertThat(fields(terminal)).containsEntry(
                    "outcome",
                    "failed"
            )
                    .containsEntry(
                            "errorCode",
                            "WORKSPACE_ACCESS_DENIED"
                    )
                    .containsEntry(
                            "failedStage",
                            "operation"
                    );
            assertThat(terminal.getFormattedMessage()).contains("SQLException")
                    .doesNotContain("private-sql-and-token");
            assertThat(terminal.getThrowableProxy()).isNull();
        }
    }

    @Test
    @DisplayName("타입이 없는 사용자 오류 문자열을 안전한 코드로 바꾼다")
    void unsafeCode_isNotLogged() {
        // given
        ErrorCode unsafeCode = new ErrorCode() {
            @Override
            public ErrorCategory getCategory() {
                return ErrorCategory.INVALID_INPUT;
            }
            @Override
            public String getCode() {
                return "private-user-code";
            }
            @Override
            public String getMessage() {
                return "private-error-message";
            }
        };
        TestProjectException failure = new TestProjectException(unsafeCode);
        try (LogCapture logs = new LogCapture(WorkspaceLog.class)) {
            // when
            assertThatThrownBy(
                    () -> WorkspaceLog.begin(
                            "workspace.detail",
                            7L
                    )
                            .run(() -> {
                                throw failure;
                            })
            ).isSameAs(failure);
            // then
            ILoggingEvent terminal = logs.events()
                    .getLast();
            assertThat(fields(terminal)).containsEntry(
                    "errorCode",
                    "UNKNOWN_FAILURE"
            );
            assertThat(terminal.getFormattedMessage()).doesNotContain(
                    "private-user-code",
                    "private-error-message"
            );
        }
    }

    @Test
    @DisplayName("뜻밖의 런타임 오류는 안전한 내부 오류 코드로 기록한다")
    void unexpectedFailure_isPreserved() {
        // given
        IllegalStateException failure = new IllegalStateException("private-payload");
        try (LogCapture logs = new LogCapture(WorkspaceLog.class)) {
            // when
            assertThatThrownBy(
                    () -> WorkspaceLog.begin(
                            "workspace.leave",
                            7L
                    )
                            .run(() -> {
                                throw failure;
                            })
            ).isSameAs(failure);
            // then
            ILoggingEvent terminal = logs.events()
                    .getLast();
            assertThat(fields(terminal)).containsEntry(
                    "outcome",
                    "failed"
            )
                    .containsEntry(
                            "errorCode",
                            "INTERNAL_SERVER_ERROR"
                    );
            assertThat(terminal.getLevel()).isEqualTo(Level.ERROR);
            assertThat(terminal.getFormattedMessage()).doesNotContain("private-payload");
        }
    }

    @Test
    @DisplayName("로그 출력 자체가 실패해도 반환값과 DB 커밋을 바꾸지 않는다")
    @SuppressWarnings("unchecked")
    void logFailure_doesNotChangeBusinessResult() throws SQLException {
        // given
        Logger logger = (Logger) LoggerFactory.getLogger(WorkspaceLog.class);
        Appender<ILoggingEvent> broken = mock(Appender.class);
        doThrow(new IllegalStateException("broken-appender")).when(broken)
                .doAppend(any());
        logger.addAppender(broken);
        try {
            // when
            String result = transaction.execute(
                    status -> WorkspaceLog.begin(
                            "workspace.list",
                            null
                    )
                            .call(() -> "business-result")
            );
            // then
            assertThat(result).isEqualTo("business-result");
            verify(connection).commit();
        } finally {
            logger.detachAppender(broken);
        }
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

    private static final class TestProjectException extends ProjectException {
        @Serial
        private static final long serialVersionUID = 1L;

        private TestProjectException(ErrorCode errorCode) {
            super(errorCode);
        }
    }
}
