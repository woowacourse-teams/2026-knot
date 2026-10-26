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
import com.knot.backend.document.application.DocumentTopicRestorationWorker;
import com.knot.backend.document.infrastructure.DocumentTopicScheduler;
import com.knot.backend.global.infrastructure.llm.LlmClient;
import com.knot.backend.testsupport.TestApplicationProperties;
import com.knot.backend.testsupport.TestcontainersConfiguration;
import jakarta.servlet.http.Cookie;
import java.sql.Timestamp;
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
class DocumentTopicRestorationAcceptanceTest {
    private static final String PATH = "/api/v1/workspaces/{workspaceId}/document-topics/restorations";

    @Autowired
    private MockMvc mvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private JdbcClient client;
    @Autowired
    private AuthTokenProvider tokens;
    @Autowired
    private DocumentTopicRestorationWorker worker;
    @MockitoBean
    private DocumentTopicScheduler scheduler;
    @MockitoBean
    private LlmClient llm;
    private DocumentFixtures fixtures;
    private long owner;
    private long workspace;
    private long version;
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
                "UPDATE workspace_members SET role='OWNER' WHERE workspace_id=?",
                workspace
        );
        long recording = fixtures.saveRecording(
                workspace,
                owner,
                60000
        );
        long transcript = fixtures.saveTranscript(recording);
        long job = fixtures.saveJob(
                transcript,
                "SUCCEEDED"
        );
        long document = fixtures.saveDocument(
                workspace,
                recording,
                transcript,
                job,
                "안건"
        );
        long category = jdbc.queryForObject(
                "INSERT INTO document_categories(workspace_id,name,normalized_name,description,kind,created_at) VALUES (?,'녹음','녹음','녹음 품질','NORMAL',?) RETURNING id",
                Long.class,
                workspace,
                Timestamp.from(DocumentFixtures.CREATED_AT)
        );
        jdbc.update(
                "UPDATE documents SET grouping_scheme='BROAD',category_id=?,assignment_source='REORGANIZATION',assignment_rule_version='reorganization-v1' WHERE id=?",
                category,
                document
        );
        long source = fixtures.saveCompletedTopicOperation(
                workspace,
                owner
        );
        jdbc.update(
                "UPDATE document_topic_operations SET target_count=1,processed_count=1,changed_count=1,consolidated=TRUE WHERE id=?",
                source
        );
        jdbc.update(
                "INSERT INTO document_topic_operation_categories(operation_id,workspace_id,category_key,existing_category_id,name,description,selected) VALUES (?,?,'new',?,'녹음','녹음 품질',TRUE)",
                source,
                workspace,
                category
        );
        jdbc.update(
                "INSERT INTO document_topic_operation_targets(operation_id,workspace_id,document_id,before_grouping_scheme,after_key,changed) VALUES (?,?,?,'LEGACY','new',TRUE)",
                source,
                workspace,
                document
        );
        version = jdbc.queryForObject(
                "INSERT INTO document_topic_recoveries(workspace_id,version_id,source_operation_id,created_at) VALUES (?,?,?,?) RETURNING id",
                Long.class,
                workspace,
                UUID.randomUUID(),
                source,
                Timestamp.from(DocumentFixtures.CREATED_AT)
        );
        csrf = mvc.perform(get("/api/v1/auth/csrf"))
                .andReturn()
                .getResponse()
                .getCookie("XSRF-TOKEN");
        assertThat(csrf).isNotNull();
    }

    @Test
    @DisplayName("신규202·재전송200은 Long 식별자와6필드를 반환하며 성공 소진 뒤에도 같은 작업을 돌려준다")
    void start_success_replaysAfterCompletion() throws Exception {
        // given
        String body = body(
                UUID.randomUUID()
                        .toString(),
                version
        );
        // when
        String first = send(
                owner,
                workspace,
                body
        ).andExpect(status().isAccepted())
                .andExpect(jsonPath("$").value(aMapWithSize(6)))
                .andExpect(jsonPath("$.type").value("RESTORATION"))
                .andExpect(jsonPath("$.targetVersionId").value(version))
                .andExpect(jsonPath("$.operationId").isNumber())
                .andExpect(jsonPath("$.status").value("QUEUED"))
                .andReturn()
                .getResponse()
                .getContentAsString();
        // then
        assertThat(
                send(
                        owner,
                        workspace,
                        body
                ).andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString()
        ).isEqualTo(first);
        long id = jdbc.queryForObject(
                "SELECT id FROM document_topic_operations WHERE kind='RESTORE'",
                Long.class
        );
        worker.execute(
                workspace,
                id
        );
        worker.execute(
                workspace,
                id
        );
        send(
                owner,
                workspace,
                body
        ).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.targetVersionId").value(version));
        mvc.perform(
                get(
                        "/api/v1/workspaces/{workspaceId}/document-topics/status",
                        workspace
                ).cookie(cookie(owner))
        )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.operation.targetVersionId").value(version))
                .andExpect(jsonPath("$.canRestore").value(false))
                .andExpect(jsonPath("$.restorableVersion").isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"versionId\":1}", "{\"requestId\":\"not-uuid\",\"versionId\":1}",
            "{\"requestId\":\"1-1-1-1-1\",\"versionId\":1}", "{\"requestId\":\"00000000-0000-0000-0000-000000000000\"}",
            "{\"requestId\":\"00000000-0000-0000-0000-000000000000\",\"versionId\":0}",
            "{\"requestId\":\"00000000-0000-0000-0000-000000000000\",\"versionId\":-1}"})
    @DisplayName("UUID·필수값·양수 버전 검증 실패는 접수 없이400을 반환한다")
    void start_failure_invalidBody(String body) throws Exception {
        // when & then
        send(
                owner,
                workspace,
                body
        ).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        assertThat(
                jdbc.queryForObject(
                        "SELECT count(*) FROM document_topic_operations WHERE kind='RESTORE'",
                        Integer.class
                )
        ).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"{",
            "{\"requestId\":\"00000000-0000-0000-0000-000000000000\",\"versionId\":9223372036854775808}"})
    @DisplayName("깨진 JSON과 Long 범위 초과는 본문400이다")
    void start_failure_unreadableBody(String body) throws Exception {
        // when & then
        send(
                owner,
                workspace,
                body
        ).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST_BODY"));
    }

    @Test
    @DisplayName("서로 다른 충돌은 명세의409 코드로 구분한다")
    void start_failure_distinctConflicts() throws Exception {
        // given
        String request = UUID.randomUUID()
                .toString();
        // when & then
        send(
                owner,
                workspace,
                body(
                        request,
                        version + 1
                )
        ).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TOPIC_RESTORATION_VERSION_CONFLICT"));
        send(
                owner,
                workspace,
                body(
                        request,
                        version
                )
        ).andExpect(status().isAccepted());
        send(
                owner,
                workspace,
                body(
                        request,
                        version + 1
                )
        ).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TOPIC_REQUEST_ID_CONFLICT"));
        send(
                owner,
                workspace,
                body(
                        UUID.randomUUID()
                                .toString(),
                        version
                )
        ).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TOPIC_OPERATION_IN_PROGRESS"));
    }

    @Test
    @DisplayName("복원 정보가 없으면409 NOT_AVAILABLE이다")
    void start_failure_noRecovery() throws Exception {
        // given
        jdbc.update("DELETE FROM document_topic_recoveries");
        // when & then
        send(
                owner,
                workspace,
                body(
                        UUID.randomUUID()
                                .toString(),
                        version
                )
        ).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TOPIC_RESTORATION_NOT_AVAILABLE"));
    }

    @Test
    @DisplayName("로그인 누락과 잘못된 JWT는401이다")
    void start_failure_authentication() throws Exception {
        // when & then
        mvc.perform(
                post(
                        PATH,
                        workspace
                ).cookie(csrf)
                        .header(
                                "X-XSRF-TOKEN",
                                csrf.getValue()
                        )
                        .contentType("application/json")
                        .content(
                                body(
                                        UUID.randomUUID()
                                                .toString(),
                                        version
                                )
                        )
        )
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        mvc.perform(
                post(
                        PATH,
                        workspace
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
                        .contentType("application/json")
                        .content(
                                body(
                                        UUID.randomUUID()
                                                .toString(),
                                        version
                                )
                        )
        )
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_JWT"));
    }

    @Test
    @DisplayName("일반 멤버·외부 멤버·CSRF 누락은403이다")
    void start_failure_authorization() throws Exception {
        // given
        long member = fixtures.saveMember("MEMBER");
        long outsider = fixtures.saveMember("외부");
        fixtures.join(
                workspace,
                member
        );
        String body = body(
                UUID.randomUUID()
                        .toString(),
                version
        );
        // when & then
        send(
                member,
                workspace,
                body
        ).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("WORKSPACE_OWNER_REQUIRED"));
        send(
                outsider,
                workspace,
                body
        ).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("WORKSPACE_ACCESS_DENIED"));
        mvc.perform(
                post(
                        PATH,
                        workspace
                ).cookie(cookie(owner))
                        .contentType("application/json")
                        .content(body)
        )
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("없는 Workspace와 잘못된 경로는404·400이다")
    void start_failure_path() throws Exception {
        // when & then
        send(
                owner,
                99999,
                body(
                        UUID.randomUUID()
                                .toString(),
                        version
                )
        ).andExpect(status().isNotFound());
        send(
                owner,
                0,
                body(
                        UUID.randomUUID()
                                .toString(),
                        version
                )
        ).andExpect(status().isBadRequest());
        mvc.perform(
                post(
                        PATH,
                        "abc"
                ).cookie(
                        cookie(owner),
                        csrf
                )
                        .header(
                                "X-XSRF-TOKEN",
                                csrf.getValue()
                        )
                        .contentType("application/json")
                        .content(
                                body(
                                        UUID.randomUUID()
                                                .toString(),
                                        version
                                )
                        )
        )
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"));
    }

    @Test
    @DisplayName("OpenAPI에 복원 경로·필수 versionId·202·200·409 계약이 제공된다")
    void openApi_success_restorationContract() throws Exception {
        // when & then
        mvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath(
                                "$.paths['/api/v1/workspaces/{workspaceId}/document-topics/restorations'].post.responses['202']"
                        ).exists()
                )
                .andExpect(
                        jsonPath(
                                "$.paths['/api/v1/workspaces/{workspaceId}/document-topics/restorations'].post.responses['200']"
                        ).exists()
                );
    }

    @Test
    @DisplayName("접수 중 내부 복원 자료 오류는 명세의 공통500으로 응답하고 접수를 롤백한다")
    void start_failure_corruptRecoveryReturnsGeneric500() throws Exception {
        // given
        jdbc.update("UPDATE document_topic_operations SET target_count=2,changed_count=2 WHERE kind='REORGANIZE'");
        // when & then
        send(
                owner,
                workspace,
                body(
                        UUID.randomUUID()
                                .toString(),
                        version
                )
        ).andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_SERVER_ERROR"));
        assertThat(
                jdbc.queryForObject(
                        "SELECT count(*) FROM document_topic_operations WHERE kind='RESTORE'",
                        Integer.class
                )
        ).isZero();
        assertThat(
                jdbc.queryForObject(
                        "SELECT count(*) FROM document_topic_recoveries",
                        Integer.class
                )
        ).isEqualTo(1);
    }

    private ResultActions send(
            long member,
            long ws,
            String body
    ) throws Exception {
        return mvc.perform(
                post(
                        PATH,
                        ws
                ).cookie(
                        cookie(member),
                        csrf
                )
                        .header(
                                "X-XSRF-TOKEN",
                                csrf.getValue()
                        )
                        .contentType("application/json")
                        .content(body)
        );
    }

    private String body(
            String request,
            long targetVersion
    ) {
        return "{\"requestId\":\"" + request + "\",\"versionId\":" + targetVersion + "}";
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
