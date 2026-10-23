package com.knot.backend.document.presentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.mockito.ArgumentMatchers.any;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import com.knot.backend.auth.domain.AuthTokenProvider;
import com.knot.backend.auth.domain.model.AuthenticatedMember;
import com.knot.backend.document.DocumentFixtures;
import com.knot.backend.document.application.DocumentTopicWorker;
import com.knot.backend.document.infrastructure.DocumentTopicScheduler;
import com.knot.backend.global.infrastructure.llm.LlmClient;
import com.knot.backend.testsupport.TestApplicationProperties;
import com.knot.backend.testsupport.TestcontainersConfiguration;
import jakarta.servlet.http.Cookie;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
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
class DocumentTopicReorganizationAcceptanceTest {
    private static final String PATH = "/api/v1/workspaces/{workspaceId}/document-topics/reorganizations";

    @Autowired
    private MockMvc mvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private JdbcClient jdbcClient;
    @Autowired
    private AuthTokenProvider tokens;
    @Autowired
    private DocumentTopicWorker worker;
    @MockitoBean
    private DocumentTopicScheduler scheduler;
    @MockitoBean
    private LlmClient llm;
    @MockitoBean
    private Clock clock;
    private long workspace;
    private long owner;
    private Cookie csrf;
    private DocumentFixtures fixtures;

    @BeforeEach
    void setUp() throws Exception {
        when(clock.instant()).thenReturn(Instant.now());
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
        csrf = mvc.perform(get("/api/v1/auth/csrf"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getCookie("XSRF-TOKEN");
        assertThat(csrf).isNotNull();
    }

    @Test
    @DisplayName("OWNER 접수202·재전송200 후 실제 파서와 작업 실행으로 목록의 큰 주제가 바뀐다")
    void start_success_endToEnd() throws Exception {
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
                "STT 품질"
        );
        UUID request = UUID.randomUUID();
        send(
                owner,
                request.toString()
        ).andExpect(status().isAccepted())
                .andExpect(jsonPath("$.targetCount").value(1))
                .andExpect(jsonPath("$.status").value("QUEUED"))
                .andExpect(jsonPath("$.operationId").isString());
        send(
                owner,
                request.toString()
        ).andExpect(status().isOk());
        when(llm.complete(any())).thenReturn(
                "{\"assignments\":[{\"documentId\":" + document
                        + ",\"categoryKey\":\"n:recording\"}],\"newCategories\":[{\"key\":\"n:recording\",\"name\":\"녹음·전사\",\"description\":\"녹음 수집과 전사 품질\"}],\"categoryMapping\":[]}",
                "{\"assignments\":[],\"newCategories\":[],\"categoryMapping\":[{\"sourceKey\":\"n:recording\",\"targetKey\":\"n:recording\"}]}"
        );
        long id = jdbc.queryForObject(
                "SELECT id FROM document_topic_operations",
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
        worker.execute(
                workspace,
                id
        );
        assertThat(
                jdbc.queryForObject(
                        "SELECT status FROM document_topic_operations",
                        String.class
                )
        ).isEqualTo("SUCCEEDED");
        mvc.perform(
                get(
                        "/api/v1/workspaces/{workspaceId}/documents",
                        workspace
                ).cookie(cookie(owner))
        )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].topic").value("녹음·전사"));
        assertThat(
                jdbc.queryForObject(
                        "SELECT topic FROM documents",
                        String.class
                )
        ).isEqualTo("STT 품질");
        send(
                owner,
                request.toString()
        ).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCEEDED"));
        verify(
                llm,
                times(2)
        ).complete(any());
    }

    @Test
    @DisplayName("유효 CSRF가 있어도 미인증이면401이다")
    void start_failure_unauthenticated() throws Exception {
        mvc.perform(
                post(
                        PATH,
                        workspace
                ).cookie(csrf)
                        .header(
                                "X-XSRF-TOKEN",
                                csrf.getValue()
                        )
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"" + UUID.randomUUID() + "\"}")
        )
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    @DisplayName("MEMBER와 비멤버는 접수할 수 없다")
    void start_failure_permissions() throws Exception {
        long member = fixtures.saveMember("MEMBER");
        fixtures.join(
                workspace,
                member
        );
        send(
                member,
                UUID.randomUUID()
                        .toString()
        ).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("WORKSPACE_OWNER_REQUIRED"));
        long outsider = fixtures.saveMember("외부");
        send(
                outsider,
                UUID.randomUUID()
                        .toString()
        ).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("WORKSPACE_ACCESS_DENIED"));
    }

    @Test
    @DisplayName("누락·잘못된 UUID와 CSRF를 거절한다")
    void start_failure_invalidInput() throws Exception {
        send(
                owner,
                "bad-uuid"
        ).andExpect(status().isBadRequest());
        mvc.perform(
                post(
                        PATH,
                        workspace
                ).cookie(
                        cookie(owner),
                        csrf
                )
                        .header(
                                "X-XSRF-TOKEN",
                                csrf.getValue()
                        )
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}")
        )
                .andExpect(status().isBadRequest());
        mvc.perform(
                post(
                        PATH,
                        workspace
                ).cookie(cookie(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"" + UUID.randomUUID() + "\"}")
        )
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("진행 중 다른 요청은409이며 같은 요청은200이다")
    void start_failure_conflict() throws Exception {
        UUID request = UUID.randomUUID();
        send(
                owner,
                request.toString()
        ).andExpect(status().isAccepted());
        send(
                owner,
                UUID.randomUUID()
                        .toString()
        ).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TOPIC_OPERATION_CONFLICT"));
        send(
                owner,
                request.toString()
        ).andExpect(status().isOk());
    }

    private ResultActions send(
            long member,
            String request
    ) throws Exception {
        return mvc.perform(
                post(
                        PATH,
                        workspace
                ).cookie(
                        cookie(member),
                        csrf
                )
                        .header(
                                "X-XSRF-TOKEN",
                                csrf.getValue()
                        )
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"" + request + "\"}")
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
}
