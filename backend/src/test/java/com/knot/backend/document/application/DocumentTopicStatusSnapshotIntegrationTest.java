package com.knot.backend.document.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doCallRealMethod;

import com.knot.backend.document.DocumentFixtures;
import com.knot.backend.document.application.dto.result.DocumentTopicStatusResult;
import com.knot.backend.document.domain.model.DocumentTopicOperation;
import com.knot.backend.document.domain.repository.DocumentTopicOperationRepository;
import com.knot.backend.document.infrastructure.persistence.DocumentTopicStatusQueryAdapter;
import com.knot.backend.testsupport.TestApplicationProperties;
import com.knot.backend.testsupport.TestcontainersConfiguration;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Tag("integration")
@ActiveProfiles("dev")
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@TestApplicationProperties
class DocumentTopicStatusSnapshotIntegrationTest {
    private static final Instant NOW = Instant.parse("2026-10-23T00:00:00.123456Z");

    @Autowired
    private DocumentTopicStatusService service;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private JdbcClient client;
    @Autowired
    private PlatformTransactionManager transactions;
    @Autowired
    private DocumentTopicOperationRepository operations;
    @Autowired
    private DocumentTopicStore store;
    @MockitoSpyBean
    private DocumentTopicStatusQueryAdapter query;
    private long workspace;
    private long owner;

    @BeforeEach
    void setUp() {
        jdbc.execute("TRUNCATE members,workspaces RESTART IDENTITY CASCADE");
        DocumentFixtures fixtures = new DocumentFixtures(client);
        owner = fixtures.saveMember("OWNER");
        workspace = fixtures.saveWorkspace();
        fixtures.join(
                workspace,
                owner
        );
        jdbc.update(
                "UPDATE workspace_members SET role='OWNER' WHERE member_id=?",
                owner
        );
    }

    @Test
    @DisplayName("작업 조회 뒤 복원 포인터가 교체되어도 한 응답은 같은 시점이다")
    void find_success_concurrentRecoveryReplacement() {
        long first = completed();
        long version = recover(first);
        doAnswer(invocation -> {
            Object result = invocation.callRealMethod();
            try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
                executor.submit(
                        () -> new TransactionTemplate(transactions).executeWithoutResult(state -> recover(completed()))
                )
                        .get(
                                10,
                                TimeUnit.SECONDS
                        );
            }
            return result;
        }).when(query)
                .findOperation(workspace);
        DocumentTopicStatusResult result = service.find(
                workspace,
                owner
        );
        assertThat(
                result.operation()
                        .operationId()
        ).isEqualTo(first);
        assertThat(
                result.restorableVersion()
                        .versionId()
        ).isEqualTo(version);
        assertThat(result.canRestore()).isTrue();
        doCallRealMethod().when(query)
                .findOperation(workspace);
        DocumentTopicStatusResult next = service.find(
                workspace,
                owner
        );
        assertThat(
                next.operation()
                        .operationId()
        ).isNotEqualTo(first);
        assertThat(
                next.restorableVersion()
                        .reorganizationOperationId()
        ).isEqualTo(
                next.operation()
                        .operationId()
        );
        assertThat(
                next.restorableVersion()
                        .versionId()
        ).isNotEqualTo(version);
    }

    @Test
    @DisplayName("새 종료 이력보다 활성 작업을 우선 반환한다")
    void find_success_activeFirst() {
        UUID active = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO document_topic_operations(operation_id,workspace_id,requester_id,request_id,kind,status,created_at,deadline_at,next_attempt_at) VALUES (?,?,?,?,'REORGANIZE','QUEUED',?,?,?)",
                active,
                workspace,
                owner,
                UUID.randomUUID(),
                Timestamp.from(NOW),
                Timestamp.from(NOW.plusSeconds(1800)),
                Timestamp.from(NOW)
        );
        completed();
        assertThat(
                service.find(
                        workspace,
                        owner
                )
                        .operation()
                        .operationId()
        ).isEqualTo(
                jdbc.queryForObject(
                        "SELECT id FROM document_topic_operations WHERE operation_id=?",
                        Long.class,
                        active
                )
        );
    }

    @Test
    @DisplayName("최초 실행 시각을 실제 PostgreSQL에 저장하고 다시 읽는다")
    void find_success_startedAtRoundTrip() {
        new TransactionTemplate(transactions).executeWithoutResult(state -> {
            DocumentTopicOperation operation = DocumentTopicOperation.reorganize(
                    workspace,
                    owner,
                    UUID.randomUUID(),
                    NOW
            );
            operation.claim(NOW.plusSeconds(2));
            operations.save(operation);
        });
        assertThat(
                service.find(
                        workspace,
                        owner
                )
                        .operation()
                        .startedAt()
        ).isEqualTo(NOW.plusSeconds(2));
    }

    @Test
    @DisplayName("복원 포인터 교체는 숫자 버전 ID도 새로 발급한다")
    void replaceRecovery_success_changesPublicVersion() {
        long first = completed();
        store.replaceRecovery(
                workspace,
                first,
                NOW
        );
        long version = jdbc.queryForObject(
                "SELECT id FROM document_topic_recoveries WHERE workspace_id=?",
                Long.class,
                workspace
        );
        long second = completed();
        store.replaceRecovery(
                workspace,
                second,
                NOW.plusSeconds(1)
        );
        DocumentTopicStatusResult result = service.find(
                workspace,
                owner
        );
        assertThat(
                result.restorableVersion()
                        .versionId()
        ).isNotEqualTo(version)
                .isPositive();
        assertThat(
                result.restorableVersion()
                        .reorganizationOperationId()
        ).isEqualTo(second);
    }

    private long completed() {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO document_topic_operations(operation_id,workspace_id,requester_id,request_id,kind,status,created_at,deadline_at,next_attempt_at,finished_at,target_count,processed_count,changed_count) VALUES (?,?,?,?,'REORGANIZE','SUCCEEDED',?,?,?,?,10,10,3)",
                id,
                workspace,
                owner,
                UUID.randomUUID(),
                Timestamp.from(NOW),
                Timestamp.from(NOW.plusSeconds(1800)),
                Timestamp.from(NOW),
                Timestamp.from(NOW.plusSeconds(10))
        );
        return jdbc.queryForObject(
                "SELECT id FROM document_topic_operations WHERE operation_id=?",
                Long.class,
                id
        );
    }

    private long recover(long id) {
        UUID version = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO document_topic_recoveries(workspace_id,version_id,source_operation_id,created_at) SELECT ?,?,id,finished_at FROM document_topic_operations WHERE id=? ON CONFLICT(workspace_id) DO UPDATE SET id=EXCLUDED.id,version_id=EXCLUDED.version_id,source_operation_id=EXCLUDED.source_operation_id,created_at=EXCLUDED.created_at",
                workspace,
                version,
                id
        );
        return jdbc.queryForObject(
                "SELECT id FROM document_topic_recoveries WHERE workspace_id=?",
                Long.class,
                workspace
        );
    }
}
