package com.knot.backend.document.presentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.aMapWithSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.knot.backend.auth.domain.AuthTokenProvider;
import com.knot.backend.auth.domain.model.AuthenticatedMember;
import com.knot.backend.document.DocumentFixtures;
import com.knot.backend.document.infrastructure.DocumentTopicScheduler;
import com.knot.backend.global.infrastructure.llm.LlmClient;
import com.knot.backend.testsupport.TestApplicationProperties;
import com.knot.backend.testsupport.TestcontainersConfiguration;
import jakarta.servlet.http.Cookie;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@Tag("acceptance")
@ActiveProfiles("dev")
@SpringBootTest(properties = {"knot.llm.enabled=true", "knot.llm.base-url=http://localhost:1234",
        "knot.llm.api-token=test-token", "knot.llm.model=test-model", "knot.document-generation.worker.enabled=false",
        "knot.document-topic-reorganization.enabled=true"})
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@TestApplicationProperties
class DocumentTopicCancellationAcceptanceTest {
    private static final String PATH = "/api/v1/workspaces/{workspaceId}/document-topics/operations/{operationId}/cancel";

    @Autowired
    private MockMvc mvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private JdbcClient client;
    @Autowired
    private AuthTokenProvider tokens;
    @MockitoBean
    private DocumentTopicScheduler scheduler;
    @MockitoBean
    private LlmClient llm;
    private DocumentFixtures fixtures;
    private long owner;
    private long workspace;
    private Cookie csrf;

    @BeforeEach
    void setUp() throws Exception {
        jdbc.execute("TRUNCATE members,workspaces RESTART IDENTITY CASCADE");
        fixtures = new DocumentFixtures(client);
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
        csrf = mvc.perform(get("/api/v1/auth/csrf"))
                .andReturn()
                .getResponse()
                .getCookie("XSRF-TOKEN");
        assertThat(csrf).isNotNull();
    }

    @Test
    @DisplayName("본문과 requestId 없이 취소를 접수하고 재요청에도 최초 시각을 유지한다")
    void cancel_success_replaysRequest() throws Exception {
        // given
        long id = operation("QUEUED");
        // when
        String first = send(
                owner,
                workspace,
                id
        ).andExpect(status().isAccepted())
                .andExpect(jsonPath("$").value(aMapWithSize(6)))
                .andExpect(jsonPath("$.operationId").value(id))
                .andExpect(jsonPath("$.type").value("REORGANIZATION"))
                .andExpect(jsonPath("$.status").value("CANCEL_REQUESTED"))
                .andExpect(jsonPath("$.cancelRequestedAt").isString())
                .andExpect(jsonPath("$.finishedAt").isEmpty())
                .andReturn()
                .getResponse()
                .getContentAsString();
        // then
        String replay = send(
                owner,
                workspace,
                id
        ).andExpect(status().isAccepted())
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(replay).isEqualTo(first);
        mvc.perform(
                get(
                        "/api/v1/workspaces/{workspaceId}/document-topics/status",
                        workspace
                ).cookie(cookie(owner))
        )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.operation.status").value("CANCEL_REQUESTED"))
                .andExpect(jsonPath("$.operation.cancellable").value(false))
                .andExpect(jsonPath("$.operation.failure").isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"APPLYING", "SUCCEEDED", "FAILED"})
    @DisplayName("최종 적용 중이거나 성공·실패로 끝난 작업은 취소409다")
    void cancel_failure_notCancellable(String state) throws Exception {
        // given
        long id = operation(state);
        // when & then
        send(
                owner,
                workspace,
                id
        ).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TOPIC_OPERATION_NOT_CANCELLABLE"));
    }

    @Test
    @DisplayName("완료된 복원 작업 취소의 재전송은 200과 기존 완료 시각을 반환한다")
    void cancel_success_completedRestoration() throws Exception {
        // given
        long id = operation("QUEUED");
        jdbc.update(
                "UPDATE document_topic_operations SET kind='RESTORE',status='CANCELLED',cancel_requested_at=created_at,finished_at=created_at WHERE id=?",
                id
        );
        // when & then
        send(
                owner,
                workspace,
                id
        ).andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("RESTORATION"))
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.finishedAt").isString());
    }

    @Test
    @DisplayName("다른 Workspace의 작업과 존재하지 않는 작업을 같은404로 처리한다")
    void cancel_failure_foreignOperation() throws Exception {
        // given
        long id = operation("QUEUED");
        long another = fixtures.saveWorkspace();
        fixtures.join(
                another,
                owner
        );
        jdbc.update(
                "UPDATE workspace_members SET role='OWNER' WHERE workspace_id=?",
                another
        );
        // when & then
        send(
                owner,
                another,
                id
        ).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("TOPIC_OPERATION_NOT_FOUND"));
        send(
                owner,
                workspace,
                Long.MAX_VALUE
        ).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("TOPIC_OPERATION_NOT_FOUND"));
        send(
                owner,
                Long.MAX_VALUE,
                id
        ).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("WORKSPACE_NOT_FOUND"));
    }

    @Test
    @DisplayName("일반 멤버·비멤버는 취소하지 못한다")
    void cancel_failure_permission() throws Exception {
        // given
        long id = operation("QUEUED");
        long member = fixtures.saveMember("MEMBER");
        long outsider = fixtures.saveMember("OUTSIDE");
        fixtures.join(
                workspace,
                member
        );
        // when & then
        send(
                member,
                workspace,
                id
        ).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("WORKSPACE_OWNER_REQUIRED"));
        send(
                outsider,
                workspace,
                id
        ).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("WORKSPACE_ACCESS_DENIED"));
    }

    @Test
    @DisplayName("본문 없는 POST도 로그인과 CSRF를 검증한다")
    void cancel_failure_security() throws Exception {
        // given
        long id = operation("QUEUED");
        // when & then
        mvc.perform(
                post(
                        PATH,
                        workspace,
                        id
                ).cookie(csrf)
                        .header(
                                "X-XSRF-TOKEN",
                                csrf.getValue()
                        )
        )
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        mvc.perform(
                post(
                        PATH,
                        workspace,
                        id
                ).cookie(
                        csrf,
                        new Cookie(
                                "KNOT_ACCESS_TOKEN",
                                "invalid"
                        )
                )
                        .header(
                                "X-XSRF-TOKEN",
                                csrf.getValue()
                        )
        )
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_JWT"));
        mvc.perform(
                post(
                        PATH,
                        workspace,
                        id
                ).cookie(cookie(owner))
        )
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mvc.perform(
                post(
                        PATH,
                        workspace,
                        id
                ).cookie(
                        cookie(owner),
                        csrf
                )
                        .header(
                                "X-XSRF-TOKEN",
                                "wrong"
                        )
        )
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("작업 경로는 양수 Long이어야 한다")
    void cancel_failure_invalidPath() throws Exception {
        // when & then
        send(
                owner,
                workspace,
                0
        ).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        send(
                owner,
                workspace,
                "not-a-number"
        ).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"));
        send(
                owner,
                workspace,
                "9223372036854775808"
        ).andExpect(status().isBadRequest());
    }

    private ResultActions send(
            long member,
            long ws,
            Object id
    ) throws Exception {
        return mvc.perform(
                post(
                        PATH,
                        ws,
                        id
                ).cookie(
                        cookie(member),
                        csrf
                )
                        .header(
                                "X-XSRF-TOKEN",
                                csrf.getValue()
                        )
        );
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

    private long operation(String state) {
        Instant now = Instant.now();
        long id = jdbc.queryForObject(
                "INSERT INTO document_topic_operations(operation_id,workspace_id,requester_id,request_id,kind,status,created_at,deadline_at,next_attempt_at) VALUES (?,?,?,?,'REORGANIZE','QUEUED',?,?,?) RETURNING id",
                Long.class,
                UUID.randomUUID(),
                workspace,
                owner,
                UUID.randomUUID(),
                Timestamp.from(now),
                Timestamp.from(now.plusSeconds(1800)),
                Timestamp.from(now)
        );
        if (state.equals("APPLYING")) {
            jdbc.update(
                    "UPDATE document_topic_operations SET status='APPLYING',claim_token=?,lease_until=? WHERE id=?",
                    UUID.randomUUID(),
                    Timestamp.from(now.plusSeconds(90)),
                    id
            );
            return id;
        }
        if (!state.equals("QUEUED")) {
            jdbc.update(
                    "UPDATE document_topic_operations SET status=?,finished_at=created_at WHERE id=?",
                    state,
                    id
            );
        }
        return id;
    }
}
