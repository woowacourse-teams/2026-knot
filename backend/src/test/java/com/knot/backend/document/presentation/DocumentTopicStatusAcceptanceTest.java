package com.knot.backend.document.presentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

import com.knot.backend.auth.domain.AuthTokenProvider;
import com.knot.backend.auth.domain.model.AuthenticatedMember;
import com.knot.backend.document.DocumentFixtures;
import com.knot.backend.document.infrastructure.DocumentTopicScheduler;
import com.knot.backend.global.infrastructure.llm.LlmClient;
import com.knot.backend.testsupport.TestApplicationProperties;
import com.knot.backend.testsupport.TestcontainersConfiguration;
import jakarta.servlet.http.Cookie;
import java.time.Instant;
import java.sql.Timestamp;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.mock.web.MockHttpServletResponse;

@Tag("acceptance")
@ActiveProfiles("dev")
@SpringBootTest(properties = {"knot.llm.enabled=true", "knot.llm.base-url=http://localhost:1234",
        "knot.llm.api-token=test-token", "knot.llm.model=test-model", "knot.document-generation.worker.enabled=false",
        "knot.document-topic-reorganization.enabled=true"})
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@TestApplicationProperties
class DocumentTopicStatusAcceptanceTest {
    private static final String PATH = "/api/v1/workspaces/{workspaceId}/document-topics/status";
    private static final Instant NOW = Instant.parse("2026-10-23T00:00:00Z");

    @Autowired
    private MockMvc mvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private JdbcClient jdbcClient;
    @Autowired
    private AuthTokenProvider tokens;
    @MockitoBean
    private DocumentTopicScheduler scheduler;
    @MockitoBean
    private LlmClient llm;
    private DocumentFixtures fixtures;
    private long workspace;
    private long owner;

    @BeforeEach
    void setUp() {
        jdbc.execute("TRUNCATE members,workspaces RESTART IDENTITY CASCADE");
        fixtures = new DocumentFixtures(jdbcClient);
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
    @DisplayName("OWNER는 CSRF 없이 작업 없는 상태를 조회하며 캐시하지 않는다")
    void find_success_empty() throws Exception {
        mvc.perform(
                get(
                        PATH,
                        workspace
                ).cookie(cookie(owner))
        )
                .andExpect(status().isOk())
                .andExpect(
                        header().string(
                                "Cache-Control",
                                "no-store"
                        )
                )
                .andExpect(jsonPath("$.workspaceId").value(workspace))
                .andExpect(jsonPath("$.operation").isEmpty())
                .andExpect(jsonPath("$.restorableVersion").isEmpty())
                .andExpect(jsonPath("$.canRestore").value(false));
    }

    @Test
    @DisplayName("실행 중 OpenAPI에 상태 조회 경로와 Long 응답을 노출한다")
    void find_success_openApi() throws Exception {
        mvc.perform(get("/v3/api-docs").cookie(cookie(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/v1/workspaces/{workspaceId}/document-topics/status'].get").exists());
    }

    @ParameterizedTest
    @CsvSource({"QUEUED,true", "RUNNING,true", "APPLYING,false", "CANCEL_REQUESTED,false", "CANCELLED,false",
            "SUCCEEDED,false", "FAILED,false"})
    @DisplayName("일곱 상태의 진행 수와 취소 가능 여부를 반환한다")
    void find_success_status(
            String state,
            boolean cancellable
    ) throws Exception {
        UUID id = operation(
                workspace,
                state,
                0,
                "secret exception"
        );
        MockHttpServletResponse response = mvc.perform(
                get(
                        PATH,
                        workspace
                ).cookie(cookie(owner))
        )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.operation.operationId").value(operationId(id)))
                .andExpect(jsonPath("$.operation.operationId").isNumber())
                .andExpect(jsonPath("$.operation.type").value("REORGANIZATION"))
                .andExpect(jsonPath("$.operation.status").value(state))
                .andExpect(jsonPath("$.operation.cancellable").value(cancellable))
                .andExpect(jsonPath("$.operation.targetDocumentCount").value(10))
                .andExpect(jsonPath("$.operation.processedDocumentCount").value(10))
                .andReturn()
                .getResponse();
        assertThat(response.getContentAsString()).doesNotContain("secret exception");
    }

    @Test
    @DisplayName("자동 재시도 대기는 RUNNING이며 최종 실패 안내를 노출하지 않는다")
    void find_success_retry() throws Exception {
        operation(
                workspace,
                "QUEUED",
                1,
                "LLM_TIMEOUT"
        );
        mvc.perform(
                get(
                        PATH,
                        workspace
                ).cookie(cookie(owner))
        )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.operation.status").value("RUNNING"))
                .andExpect(jsonPath("$.operation.failure").isEmpty())
                .andExpect(jsonPath("$.operation.changedDocumentCount").isEmpty());
    }

    @Test
    @DisplayName("최종 실패도 정상 조회하며 작업 종류별 안전한 안내를 제공한다")
    void find_success_failedRestoration() throws Exception {
        operation(
                workspace,
                "FAILED",
                1,
                "private database error"
        );
        jdbc.update("UPDATE document_topic_operations SET kind='RESTORE'");
        mvc.perform(
                get(
                        PATH,
                        workspace
                ).cookie(cookie(owner))
        )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.operation.failure.code").value("TOPIC_RESTORATION_FAILED"))
                .andExpect(jsonPath("$.operation.failure.message").value("직전 정리를 되돌리지 못해 현재 분류를 유지했어요. 다시 시도해 주세요."));
    }

    @Test
    @DisplayName("새 실패 이후에도 이전 성공의 복원 버전과 변경 수를 유지한다")
    void find_success_previousRecovery() throws Exception {
        UUID success = operation(
                workspace,
                "SUCCEEDED",
                0,
                null
        );
        UUID version = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO document_topic_recoveries(workspace_id,version_id,source_operation_id,created_at) SELECT ?,?,id,finished_at FROM document_topic_operations WHERE operation_id=?",
                workspace,
                version,
                success
        );
        long publicVersion = jdbc.queryForObject(
                "SELECT id FROM document_topic_recoveries WHERE workspace_id=?",
                Long.class,
                workspace
        );
        UUID failed = operation(
                workspace,
                "FAILED",
                1,
                "unknown"
        );
        mvc.perform(
                get(
                        PATH,
                        workspace
                ).cookie(cookie(owner))
        )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.operation.operationId").value(operationId(failed)))
                .andExpect(jsonPath("$.restorableVersion.versionId").value(publicVersion))
                .andExpect(jsonPath("$.restorableVersion.reorganizationOperationId").value(operationId(success)))
                .andExpect(jsonPath("$.restorableVersion.affectedDocumentCount").value(3))
                .andExpect(jsonPath("$.canRestore").value(true));
        operation(
                workspace,
                "RUNNING",
                0,
                null
        );
        mvc.perform(
                get(
                        PATH,
                        workspace
                ).cookie(cookie(owner))
        )
                .andExpect(jsonPath("$.restorableVersion.versionId").value(publicVersion))
                .andExpect(jsonPath("$.canRestore").value(false));
    }

    @Test
    @DisplayName("다른 Workspace 이력은 조회하지 않는다")
    void find_success_workspaceIsolation() throws Exception {
        operation(
                fixtures.saveWorkspace(),
                "RUNNING",
                0,
                null
        );
        mvc.perform(
                get(
                        PATH,
                        workspace
                ).cookie(cookie(owner))
        )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.operation").isEmpty());
    }

    @Test
    @DisplayName("승계한 OWNER는 이전 요청자의 작업을 조회하고 탈퇴자는 거절한다")
    void find_success_transferredOwner() throws Exception {
        UUID id = operation(
                workspace,
                "QUEUED",
                0,
                null
        );
        long successor = fixtures.saveMember("새 OWNER");
        fixtures.join(
                workspace,
                successor
        );
        jdbc.update(
                "UPDATE workspace_members SET role='MEMBER' WHERE member_id=?",
                owner
        );
        jdbc.update(
                "UPDATE workspace_members SET role='OWNER' WHERE member_id=?",
                successor
        );
        mvc.perform(
                get(
                        PATH,
                        workspace
                ).cookie(cookie(successor))
        )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.operation.operationId").value(operationId(id)));
        mvc.perform(
                get(
                        PATH,
                        workspace
                ).cookie(cookie(owner))
        )
                .andExpect(status().isForbidden());
        jdbc.update(
                "UPDATE workspace_members SET left_at=CURRENT_TIMESTAMP,last_viewed=FALSE WHERE member_id=?",
                owner
        );
        mvc.perform(
                get(
                        PATH,
                        workspace
                ).cookie(cookie(owner))
        )
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("WORKSPACE_ACCESS_DENIED"));
    }

    @Test
    @DisplayName("삭제된 Workspace는 이력을 노출하지 않는다")
    void find_failure_deletedWorkspace() throws Exception {
        operation(
                workspace,
                "FAILED",
                1,
                "private"
        );
        jdbc.update(
                "UPDATE workspaces SET deleted_at=CURRENT_TIMESTAMP WHERE id=?",
                workspace
        );
        mvc.perform(
                get(
                        PATH,
                        workspace
                ).cookie(cookie(owner))
        )
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("WORKSPACE_NOT_FOUND"));
    }

    @Test
    @DisplayName("미인증 MEMBER 비멤버와 없는 Workspace를 구분한다")
    void find_failure_permissions() throws Exception {
        mvc.perform(
                get(
                        PATH,
                        workspace
                )
        )
                .andExpect(status().isUnauthorized());
        long member = fixtures.saveMember("MEMBER");
        mvc.perform(
                get(
                        PATH,
                        workspace
                ).cookie(cookie(member))
        )
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("WORKSPACE_ACCESS_DENIED"));
        fixtures.join(
                workspace,
                member
        );
        mvc.perform(
                get(
                        PATH,
                        workspace
                ).cookie(cookie(member))
        )
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("WORKSPACE_OWNER_REQUIRED"));
        mvc.perform(
                get(
                        PATH,
                        99999
                ).cookie(cookie(owner))
        )
                .andExpect(status().isNotFound());
        mvc.perform(
                get(
                        PATH,
                        0
                ).cookie(cookie(owner))
        )
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("잘못된 인증 쿠키는 명세의 INVALID_JWT로 구분한다")
    void find_failure_invalidJwt() throws Exception {
        mvc.perform(
                get(
                        PATH,
                        workspace
                ).cookie(
                        new Cookie(
                                "KNOT_ACCESS_TOKEN",
                                "invalid-token"
                        )
                )
        )
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_JWT"));
    }

    @Test
    @DisplayName("잘못된 경로 타입과 양수 범위 오류는 명세대로 구분한다")
    void find_failure_invalidPath() throws Exception {
        mvc.perform(
                get(
                        PATH,
                        "not-a-number"
                ).cookie(cookie(owner))
        )
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"));
        mvc.perform(
                get(
                        PATH,
                        0
                ).cookie(cookie(owner))
        )
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    private long operationId(UUID uuid) {
        return jdbc.queryForObject(
                "SELECT id FROM document_topic_operations WHERE operation_id=?",
                Long.class,
                uuid
        );
    }

    private UUID operation(
            long ws,
            String state,
            int retry,
            String failure
    ) {
        UUID id = UUID.randomUUID();
        Instant finished = null;
        UUID token = null;
        Instant lease = null;
        if (java.util.Set.of(
                "FAILED",
                "SUCCEEDED",
                "CANCELLED"
        )
                .contains(state)) {
            finished = NOW.plusSeconds(10);
        }
        if (java.util.Set.of(
                "RUNNING",
                "APPLYING"
        )
                .contains(state)) {
            token = UUID.randomUUID();
            lease = NOW.plusSeconds(90);
        }
        jdbc.update(
                "INSERT INTO document_topic_operations(operation_id,workspace_id,requester_id,request_id,kind,status,created_at,deadline_at,next_attempt_at,finished_at,claim_token,lease_until,retry_count,failure_code,target_count,processed_count,changed_count) VALUES (?,?,?,?,'REORGANIZE',?,?,?,?,?,?,?,?,?,10,10,3)",
                id,
                ws,
                owner,
                UUID.randomUUID(),
                state,
                Timestamp.from(NOW),
                Timestamp.from(NOW.plusSeconds(1800)),
                Timestamp.from(NOW),
                timestamp(finished),
                token,
                timestamp(lease),
                retry,
                failure
        );
        return id;
    }

    private Timestamp timestamp(Instant value) {
        if (value == null) {
            return null;
        }
        return Timestamp.from(value);
    }

    private Cookie cookie(long member) {
        return new Cookie(
                "KNOT_ACCESS_TOKEN",
                tokens.issue(
                        AuthenticatedMember.of(
                                member,
                                "멤버",
                                null
                        )
                )
        );
    }
}
