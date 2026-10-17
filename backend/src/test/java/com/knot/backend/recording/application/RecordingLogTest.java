package com.knot.backend.recording.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import com.knot.backend.global.exception.ErrorCategory;
import com.knot.backend.global.exception.ErrorCode;
import com.knot.backend.global.exception.ProjectException;
import com.knot.backend.recording.domain.RecordingErrorCode;
import com.knot.backend.recording.domain.RecordingException;
import com.knot.backend.testsupport.LogCapture;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.MDC;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class RecordingLogTest {
    private final UUID attemptId = UUID.randomUUID();
    private final Connection connection = mock(Connection.class);
    private TransactionTemplate transaction;

    @BeforeEach
    void prepare() throws SQLException {
        DataSource source = mock(DataSource.class);
        when(source.getConnection()).thenReturn(connection);
        when(connection.getAutoCommit()).thenReturn(true);
        transaction = new TransactionTemplate(new DataSourceTransactionManager(source));
        MDC.put(
                "requestId",
                "request-at-service-start"
        );
    }

    @AfterEach
    void close() {
        MDC.remove("requestId");
    }

    @ParameterizedTest
    @ValueSource(strings = {"created", "completed"})
    @DisplayName("저장 성공은 실제 JDBC 커밋 이후 기록하고 시작 시 요청 ID를 보존한다")
    void scope_success_commitPublishesTerminalWithStructuredFields(String outcome) throws SQLException {
        // given
        try (LogCapture logs = new LogCapture(RecordingLog.class)) {
            // when
            transaction.executeWithoutResult(status -> {
                try (RecordingLog.Scope scope = scope()) {
                    scope.outcome(outcome);
                }
                assertThat(logs.events()).singleElement()
                        .satisfies(
                                event -> assertThat(fields(event)).containsEntry(
                                        "outcome",
                                        "started"
                                )
                        );
                MDC.put(
                        "requestId",
                        "different-request-at-commit"
                );
            });

            // then
            verify(connection).commit();
            assertThat(logs.events()).hasSize(2);
            ILoggingEvent terminal = logs.events()
                    .getLast();
            assertThat(terminal.getLevel()).isEqualTo(Level.INFO);
            assertThat(fields(terminal)).containsEntry(
                    "event",
                    "recording.start"
            )
                    .containsEntry(
                            "outcome",
                            outcome
                    )
                    .containsEntry(
                            "requestId",
                            "request-at-service-start"
                    )
                    .containsEntry(
                            "workspaceId",
                            10L
                    )
                    .containsEntry(
                            "recordingId",
                            42L
                    )
                    .containsEntry(
                            "uploadId",
                            7L
                    )
                    .containsEntry(
                            "attemptId",
                            attemptId.toString()
                    );
            assertThat((Long) fields(terminal).get("elapsedMs")).isNotNegative();
            assertThat(
                    fields(
                            logs.events()
                                    .getFirst()
                    )
            ).doesNotContainKey("elapsedMs");
            assertJsonMatchesFields(terminal);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"created", "completed"})
    @DisplayName("롤백된 저장은 created 또는 completed 성공으로 기록하지 않는다")
    void scope_failure_rollbackDoesNotPublishSuccess(String outcome) throws SQLException {
        // given
        try (LogCapture logs = new LogCapture(RecordingLog.class)) {
            // when
            transaction.executeWithoutResult(status -> {
                try (RecordingLog.Scope scope = scope()) {
                    scope.outcome(outcome);
                }
                status.setRollbackOnly();
            });

            // then
            verify(connection).rollback();
            assertThat(logs.events()).hasSize(2);
            ILoggingEvent terminal = logs.events()
                    .getLast();
            assertThat(terminal.getLevel()).isEqualTo(Level.ERROR);
            assertThat(fields(terminal)).containsEntry(
                    "outcome",
                    "failed"
            )
                    .containsEntry(
                            "errorCode",
                            "TRANSACTION_ROLLED_BACK"
                    );
            assertJsonMatchesFields(terminal);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"created", "completed"})
    @DisplayName("JDBC 커밋 실패로 결과가 불명확하면 성공 대신 unconfirmed를 기록한다")
    void scope_failure_unknownCommitDoesNotPublishSuccess(String outcome) throws SQLException {
        // given
        doThrow(new SQLException("secret-sql-and-token")).when(connection)
                .commit();
        try (LogCapture logs = new LogCapture(RecordingLog.class)) {
            // when
            Throwable thrown = catchThrowable(() -> transaction.executeWithoutResult(status -> {
                try (RecordingLog.Scope scope = scope()) {
                    scope.outcome(outcome);
                }
            }));

            // then
            assertThat(thrown).isInstanceOf(TransactionSystemException.class);
            verify(connection).commit();
            assertThat(logs.events()).hasSize(2);
            ILoggingEvent terminal = logs.events()
                    .getLast();
            assertThat(terminal.getLevel()).isEqualTo(Level.WARN);
            assertThat(fields(terminal)).containsEntry(
                    "outcome",
                    "unconfirmed"
            )
                    .containsEntry(
                            "errorCode",
                            "TRANSACTION_UNCONFIRMED"
                    );
            assertThat(terminal.getFormattedMessage()).doesNotContain("secret-sql-and-token");
            assertJsonMatchesFields(terminal);
        }
    }

    @Test
    @DisplayName("자동 종료의 확정 사실은 커밋 이후에만 기록하고 측정하지 않은 시간은 넣지 않는다")
    void committed_success_afterCommitWithoutFabricatedElapsedTime() {
        // given
        try (LogCapture logs = new LogCapture(RecordingLog.class)) {
            // when
            transaction.executeWithoutResult(status -> {
                RecordingLog.committed(
                        "recording.auto_end",
                        "changed",
                        10,
                        42,
                        null,
                        null,
                        "CONNECTION_EXPIRED"
                );
                assertThat(logs.events()).isEmpty();
            });

            // then
            assertThat(logs.events()).singleElement()
                    .satisfies(event -> {
                        assertThat(event.getLevel()).isEqualTo(Level.INFO);
                        assertThat(fields(event)).containsEntry(
                                "event",
                                "recording.auto_end"
                        )
                                .containsEntry(
                                        "outcome",
                                        "changed"
                                )
                                .containsEntry(
                                        "endReason",
                                        "CONNECTION_EXPIRED"
                                )
                                .doesNotContainKey("elapsedMs");
                        assertJsonMatchesFields(event);
                    });
        }
    }

    @Test
    @DisplayName("자동 종료 트랜잭션이 롤백되면 자동 종료 확정 로그를 남기지 않는다")
    void committed_failure_rollbackProducesNoConfirmedEvent() {
        // given
        try (LogCapture logs = new LogCapture(RecordingLog.class)) {
            // when
            transaction.executeWithoutResult(status -> {
                RecordingLog.committed(
                        "recording.auto_end",
                        "changed",
                        10,
                        42,
                        null,
                        null,
                        "CONNECTION_EXPIRED"
                );
                status.setRollbackOnly();
            });

            // then
            assertThat(logs.events()).isEmpty();
        }
    }

    @Test
    @DisplayName("자동 종료의 커밋 결과가 불명확하면 확정 로그를 남기지 않는다")
    void committed_failure_unknownCommitProducesNoConfirmedEvent() throws SQLException {
        // given
        doThrow(new SQLException("secret-commit-payload")).when(connection)
                .commit();
        try (LogCapture logs = new LogCapture(RecordingLog.class)) {
            // when
            Throwable thrown = catchThrowable(() -> transaction.executeWithoutResult(status -> {
                RecordingLog.committed(
                        "recording.auto_end",
                        "changed",
                        10,
                        42,
                        null,
                        null,
                        "CONNECTION_EXPIRED"
                );
            }));

            // then
            assertThat(thrown).isInstanceOf(TransactionSystemException.class);
            assertThat(logs.events()).isEmpty();
        }
    }

    @Test
    @DisplayName("업무 거절은 INFO이며 예외 메시지나 원인 메시지를 출력하지 않는다")
    void call_failure_knownRejectionAtInfoWithoutMessages() {
        // given
        RecordingException failure = new RecordingException(
                RecordingErrorCode.ACTIVE_RECORDING_ALREADY_EXISTS,
                new IllegalStateException("secret-cookie")
        );
        try (LogCapture logs = new LogCapture(RecordingLog.class)) {
            // when
            Throwable thrown = catchThrowable(() -> scope().call(() -> {
                throw failure;
            }));

            // then
            assertThat(thrown).isSameAs(failure);
            ILoggingEvent terminal = logs.events()
                    .getLast();
            assertThat(terminal.getLevel()).isEqualTo(Level.INFO);
            assertThat(fields(terminal)).containsEntry(
                    "outcome",
                    "rejected"
            )
                    .containsEntry(
                            "errorCode",
                            "ACTIVE_RECORDING_ALREADY_EXISTS"
                    );
            assertThat(terminal.getFormattedMessage()).doesNotContain(
                    failure.getMessage(),
                    "secret-cookie"
            );
        }
    }

    @Test
    @DisplayName("외부 오류 코드는 허용 목록으로 제한하고 원인 조사에는 메시지 없는 진단만 남긴다")
    void call_failure_untrustedCodeAndMessagesAreExcluded() {
        // given
        ErrorCode external = new ErrorCode() {
            public ErrorCategory getCategory() {
                return ErrorCategory.INTERNAL_SERVER_ERROR;
            }
            public String getCode() {
                return "secret-provider-payload";
            }
            public String getMessage() {
                return "secret-presigned-url";
            }
        };
        ProjectException failure = new ProjectException(
                external,
                new IllegalArgumentException("secret-transcript")
        ) {
        };
        try (LogCapture logs = new LogCapture(RecordingLog.class)) {
            // when
            Throwable thrown = catchThrowable(() -> scope().call(() -> {
                throw failure;
            }));

            // then
            assertThat(thrown).isSameAs(failure);
            ILoggingEvent terminal = logs.events()
                    .getLast();
            assertThat(terminal.getLevel()).isEqualTo(Level.ERROR);
            assertThat(fields(terminal)).containsEntry(
                    "outcome",
                    "failed"
            )
                    .containsEntry(
                            "errorCode",
                            "UNKNOWN_FAILURE"
                    );
            assertThat((String) fields(terminal).get("diagnostic")).contains(IllegalArgumentException.class.getName());
            assertThat(terminal.getThrowableProxy()).isNull();
            assertThat(terminal.getFormattedMessage()).doesNotContain(
                    "secret-provider-payload",
                    "secret-presigned-url",
                    "secret-transcript"
            );
            assertJsonMatchesFields(terminal);
        }
    }

    @Test
    @DisplayName("직접 받은 외부 실패 문자열도 로그에 복사하지 않는다")
    void error_failure_untrustedRemoteStringIsExcluded() {
        // given
        try (LogCapture logs = new LogCapture(RecordingLog.class)) {
            // when
            try (RecordingLog.Scope scope = scope()) {
                scope.error("secret-token-in-remote-code")
                        .outcome("failed");
            }
            // then
            assertThat(
                    fields(
                            logs.events()
                                    .getLast()
                    )
            ).containsEntry(
                    "errorCode",
                    "UNKNOWN_FAILURE"
            );
            assertThat(
                    logs.events()
                            .getLast()
                            .getFormattedMessage()
            ).doesNotContain("secret-token-in-remote-code");
        }
    }

    @Test
    @DisplayName("원격 결과 미확정 상태를 지정했다면 런타임 예외도 확정 실패로 바꾸지 않는다")
    void call_failure_preservesExplicitUnconfirmedOutcome() {
        // given
        IllegalStateException failure = new IllegalStateException("secret-provider-body");
        try (LogCapture logs = new LogCapture(RecordingLog.class)) {
            RecordingLog.Scope scope = scope().outcome("unconfirmed");
            // when
            Throwable thrown = catchThrowable(() -> scope.call(() -> {
                throw failure;
            }));

            // then
            assertThat(thrown).isSameAs(failure);
            ILoggingEvent terminal = logs.events()
                    .getLast();
            assertThat(terminal.getLevel()).isEqualTo(Level.WARN);
            assertThat(fields(terminal)).containsEntry(
                    "outcome",
                    "unconfirmed"
            );
            assertThat(terminal.getFormattedMessage()).doesNotContain("secret-provider-body");
        }
    }

    private RecordingLog.Scope scope() {
        return RecordingLog.begin(
                "recording.start",
                10,
                42L,
                7L,
                attemptId
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

    private void assertJsonMatchesFields(ILoggingEvent event) {
        JsonNode json = JsonMapper.builder()
                .build()
                .readTree(event.getFormattedMessage());
        assertThat(json.isObject()).isTrue();
        fields(event).forEach(
                (
                        key,
                        value
                ) -> {
                    if (value == null) {
                        assertThat(
                                json.path(key)
                                        .isNull()
                        ).isTrue();
                    } else if (value instanceof Number number) {
                        assertThat(
                                json.path(key)
                                        .asLong()
                        ).isEqualTo(number.longValue());
                    } else {
                        assertThat(
                                json.path(key)
                                        .asString()
                        ).isEqualTo(value.toString());
                    }
                }
        );
    }
}
