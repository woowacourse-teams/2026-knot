package com.knot.backend.document.presentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.mockito.Mockito.when;

import com.knot.backend.auth.domain.AuthTokenProvider;
import com.knot.backend.auth.domain.model.AuthenticatedMember;
import com.knot.backend.document.DocumentFixtures;
import com.knot.backend.testsupport.TestApplicationProperties;
import com.knot.backend.testsupport.TestcontainersConfiguration;
import jakarta.servlet.http.Cookie;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@Tag("acceptance")
@ActiveProfiles("dev")
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@TestApplicationProperties
class DocumentGenerationJobRetryAcceptanceTest {
    private static final String JOBS_PATH = "/api/v1/workspaces/{workspaceId}/document-generation-jobs";
    private static final String RETRY_PATH = JOBS_PATH + "/{jobId}/retry";
    private static final Instant NOW = DocumentFixtures.CREATED_AT.plusSeconds(120);

    @Autowired
    private MockMvc mvc;
    @Autowired
    private JdbcClient jdbc;
    @Autowired
    private AuthTokenProvider tokens;
    @MockitoBean
    private Clock clock;
    private DocumentFixtures fixtures;
    private Cookie csrfCookie;
    private long memberId;
    private long workspaceId;
    private long recordingId;
    private long transcriptId;
    private long jobId;

    @BeforeEach
    void setUp() throws Exception {
        when(clock.instant()).thenReturn(NOW);
        jdbc.sql("TRUNCATE TABLE members, workspaces RESTART IDENTITY CASCADE")
                .update();
        fixtures = new DocumentFixtures(jdbc);
        memberId = fixtures.saveMember("문서 작성자");
        workspaceId = fixtures.saveWorkspace();
        fixtures.join(
                workspaceId,
                memberId
        );
        recordingId = fixtures.saveRecording(
                workspaceId,
                memberId,
                120000
        );
        transcriptId = fixtures.saveTranscript(recordingId);
        jobId = fixtures.saveJob(
                transcriptId,
                "FAILED"
        );
        csrfCookie = mvc.perform(get("/api/v1/auth/csrf"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getCookie("XSRF-TOKEN");
        assertThat(csrfCookie).isNotNull();
    }

    @Test
    @DisplayName("빈 본문 POST는 202와 기존 Job 회차를 반환하고 GET 목록에 QUEUED로 표시된다")
    void retryDocumentGenerationJob_success() throws Exception {
        // when & then
        retry(
                workspaceId,
                memberId,
                jobId
        ).andExpect(status().isAccepted())
                .andExpect(jsonPath("$.jobId").value(jobId))
                .andExpect(jsonPath("$.status").value("QUEUED"))
                .andExpect(jsonPath("$.attemptCount").value(2));
        mvc.perform(
                get(
                        JOBS_PATH,
                        workspaceId
                ).cookie(cookie(memberId))
        )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].jobId").value(jobId))
                .andExpect(jsonPath("$.items[0].status").value("QUEUED"))
                .andExpect(jsonPath("$.items[0].updatedAt").value(NOW.toString()));
        assertThat(userRetryCount()).isEqualTo(1);
        assertThat(
                jdbc.sql("SELECT count(*) FROM document_generation_jobs")
                        .query(Long.class)
                        .single()
        ).isEqualTo(1);
    }

    @Test
    @DisplayName("이미 접수된 Job의 반복 요청은 409이며 접수·사용자 횟수를 추가하지 않는다")
    void retryDocumentGenerationJob_failure_repeatedRequest() throws Exception {
        // given
        retry(
                workspaceId,
                memberId,
                jobId
        ).andExpect(status().isAccepted());

        // when & then
        retry(
                workspaceId,
                memberId,
                jobId
        ).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RETRY_NOT_ALLOWED"));
        assertThat(userRetryCount()).isEqualTo(1);
        assertThat(
                jdbc.sql("SELECT user_retry_count FROM document_generation_jobs WHERE id = :id")
                        .param(
                                "id",
                                jobId
                        )
                        .query(Integer.class)
                        .single()
        ).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"QUEUED", "RUNNING", "SUCCEEDED"})
    @DisplayName("FAILED가 아닌 상태는 재시도를 접수하지 않는다")
    void retryDocumentGenerationJob_failure_notFailed(String jobStatus) throws Exception {
        // given
        jdbc.sql("""
                UPDATE document_generation_jobs SET status = :status, failure_cause = NULL,
                    next_attempt_at = CASE WHEN :status = 'QUEUED' THEN updated_at END,
                    execution_deadline_at = CASE WHEN :status = 'RUNNING' THEN updated_at + INTERVAL '150 seconds' END
                WHERE id = :id
                """)
                .param(
                        "status",
                        jobStatus
                )
                .param(
                        "id",
                        jobId
                )
                .update();

        // when & then
        assertRetryDenied();
    }

    @ParameterizedTest
    @ValueSource(longs = {0, 1, 30 * 24 * 3600})
    @DisplayName("마지막 실패 뒤 168시간이 지나도 원문이 남아 있으면 202다")
    void retryDocumentGenerationJob_success_afterHomeDisplayPeriod(long secondsAfter) throws Exception {
        // given
        when(clock.instant()).thenReturn(DocumentFixtures.CREATED_AT.plusSeconds(168 * 3600 + secondsAfter));

        // when & then
        retry(
                workspaceId,
                memberId,
                jobId
        ).andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("QUEUED"));
        assertThat(userRetryCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("표시 기한 직전 접수한 Job은 기한 이후에도 진행 목록에 QUEUED로 유지된다")
    void retryDocumentGenerationJob_success_beforeExpiration() throws Exception {
        // given
        Instant deadline = DocumentFixtures.CREATED_AT.plusSeconds(168 * 3600);
        when(clock.instant()).thenReturn(deadline.minusNanos(1000));

        // when
        retry(
                workspaceId,
                memberId,
                jobId
        ).andExpect(status().isAccepted());
        when(clock.instant()).thenReturn(deadline.plusSeconds(1));

        // then
        mvc.perform(
                get(
                        JOBS_PATH,
                        workspaceId
                ).cookie(cookie(memberId))
        )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].status").value("QUEUED"));
    }

    @Test
    @DisplayName("사용자 재시도를 3회 한 Job도 다시 접수하고 사용자 횟수를 계속 센다")
    void retryDocumentGenerationJob_success_afterThreeUserRetries() throws Exception {
        // given
        jdbc.sql("UPDATE document_generation_jobs SET user_retry_count = 3, attempt_count = 4 WHERE id = :id")
                .param(
                        "id",
                        jobId
                )
                .update();

        // when & then
        retry(
                workspaceId,
                memberId,
                jobId
        ).andExpect(status().isAccepted())
                .andExpect(jsonPath("$.attemptCount").value(5));
        assertThat(userRetryCount()).isEqualTo(4);
    }

    @Test
    @DisplayName("자동 시도 이력이 있어도 사용자 첫 재시도를 접수하고 전체 횟수에 포함한다")
    void retryDocumentGenerationJob_success_automaticAttempts() throws Exception {
        // given
        jdbc.sql("UPDATE document_generation_jobs SET automatic_retry_count = 8, attempt_count = 9 WHERE id = :id")
                .param(
                        "id",
                        jobId
                )
                .update();

        // when & then
        retry(
                workspaceId,
                memberId,
                jobId
        ).andExpect(status().isAccepted())
                .andExpect(jsonPath("$.attemptCount").value(10));
    }

    @Test
    @DisplayName("사용할 텍스트가 없는 저장 원문은 409로 거절한다")
    void retryDocumentGenerationJob_failure_unusableTranscript() throws Exception {
        // given
        jdbc.sql("UPDATE transcripts SET content = '' WHERE id = :id")
                .param(
                        "id",
                        transcriptId
                )
                .update();

        // when & then
        assertRetryDenied();
    }

    @Test
    @DisplayName("이전 정책으로 정리되어 삭제된 Job은 404다")
    void retryDocumentGenerationJob_failure_deletedJob() throws Exception {
        // given
        jdbc.sql("DELETE FROM document_generation_jobs WHERE id = :id")
                .param(
                        "id",
                        jobId
                )
                .update();

        // when & then
        retry(
                workspaceId,
                memberId,
                jobId
        ).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("DOCUMENT_GENERATION_JOB_NOT_FOUND"));
    }

    @Test
    @DisplayName("없는 Job과 다른 Workspace의 Job은 같은 404다")
    void retryDocumentGenerationJob_failure_resourceScope() throws Exception {
        // given
        long otherWorkspace = fixtures.saveWorkspace();
        fixtures.join(
                otherWorkspace,
                memberId
        );

        // when & then
        retry(
                workspaceId,
                memberId,
                jobId + 100
        ).andExpect(status().isNotFound());
        retry(
                otherWorkspace,
                memberId,
                jobId
        ).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("DOCUMENT_GENERATION_JOB_NOT_FOUND"));
        assertThat(userRetryCount()).isZero();
    }

    @Test
    @DisplayName("비멤버와 탈퇴자는 현재 Workspace 접근 권한이 없다")
    void retryDocumentGenerationJob_failure_membership() throws Exception {
        // given
        long nonMember = fixtures.saveMember("외부 멤버");
        fixtures.leave(
                workspaceId,
                memberId
        );

        // when & then
        retry(
                workspaceId,
                nonMember,
                jobId
        ).andExpect(status().isForbidden());
        retry(
                workspaceId,
                memberId,
                jobId
        ).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("WORKSPACE_ACCESS_DENIED"));
        assertThat(userRetryCount()).isZero();
    }

    @Test
    @DisplayName("삭제된 Workspace는 과거 멤버십이 있어도 403이다")
    void retryDocumentGenerationJob_failure_deletedWorkspace() throws Exception {
        // given
        jdbc.sql("UPDATE workspaces SET deleted_at = :now WHERE id = :id")
                .param(
                        "now",
                        Timestamp.from(NOW)
                )
                .param(
                        "id",
                        workspaceId
                )
                .update();

        // when & then
        retry(
                workspaceId,
                memberId,
                jobId
        ).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("WORKSPACE_ACCESS_DENIED"));
    }

    @Test
    @DisplayName("유효한 CSRF가 있어도 로그인 쿠키가 없으면 401이다")
    void retryDocumentGenerationJob_failure_unauthenticated() throws Exception {
        // when & then
        mvc.perform(
                post(
                        RETRY_PATH,
                        workspaceId,
                        jobId
                ).cookie(csrfCookie)
                        .header(
                                "X-XSRF-TOKEN",
                                csrfCookie.getValue()
                        )
        )
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        assertThat(userRetryCount()).isZero();
    }

    @Test
    @DisplayName("로그인해도 CSRF가 누락되거나 불일치하면 403이고 접수하지 않는다")
    void retryDocumentGenerationJob_failure_csrf() throws Exception {
        // when & then
        mvc.perform(
                post(
                        RETRY_PATH,
                        workspaceId,
                        jobId
                ).cookie(cookie(memberId))
        )
                .andExpect(status().isForbidden());
        mvc.perform(
                post(
                        RETRY_PATH,
                        workspaceId,
                        jobId
                ).cookie(
                        cookie(memberId),
                        csrfCookie
                )
                        .header(
                                "X-XSRF-TOKEN",
                                "wrong-token"
                        )
        )
                .andExpect(status().isForbidden());
        assertThat(userRetryCount()).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"abc", "0", "-1", "9223372036854775808"})
    @DisplayName("Job ID의 형식과 양수 범위 오류는 400이다")
    void retryDocumentGenerationJob_failure_invalidJobId(String invalidId) throws Exception {
        // when & then
        mvc.perform(
                post(
                        RETRY_PATH,
                        workspaceId,
                        invalidId
                ).cookie(
                        cookie(memberId),
                        csrfCookie
                )
                        .header(
                                "X-XSRF-TOKEN",
                                csrfCookie.getValue()
                        )
        )
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"));
        assertThat(userRetryCount()).isZero();
    }

    @Test
    @DisplayName("실패 Job 재접수는 같은 녹음의 성공 문서·확인 기록·연결 원문을 변경하지 않는다")
    void retryDocumentGenerationJob_success_preserveOtherDocument() throws Exception {
        // given
        long successfulJob = fixtures.saveJob(
                transcriptId,
                "SUCCEEDED"
        );
        long documentId = fixtures.saveDocument(
                workspaceId,
                recordingId,
                transcriptId,
                successfulJob,
                "운영 정책"
        );
        fixtures.target(
                documentId,
                memberId,
                DocumentFixtures.CREATED_AT.plusSeconds(30)
        );
        String documentBefore = documentSnapshot(documentId);

        // when
        retry(
                workspaceId,
                memberId,
                jobId
        ).andExpect(status().isAccepted());

        // then
        assertThat(documentSnapshot(documentId)).isEqualTo(documentBefore);
        assertThat(
                jdbc.sql("SELECT confirmed_at FROM document_confirmations WHERE document_id = :id")
                        .param(
                                "id",
                                documentId
                        )
                        .query(Timestamp.class)
                        .single()
                        .toInstant()
        ).isEqualTo(DocumentFixtures.CREATED_AT.plusSeconds(30));
        mvc.perform(
                get(
                        "/api/v1/workspaces/{workspaceId}/documents/{documentId}",
                        workspaceId,
                        documentId
                ).cookie(cookie(memberId))
        )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sourceTranscriptId").value(transcriptId));
    }

    @Test
    @DisplayName("OpenAPI는 POST·202·오류·필수 CSRF 헤더·빈 본문 계약을 명시한다")
    void openApi_success_retryContract() throws Exception {
        // given
        String operation = "$.paths['" + RETRY_PATH + "'].post";
        String schema = "$.components.schemas.DocumentGenerationJobRetryResponse";

        // when & then
        mvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath(operation + ".responses['202']").exists())
                .andExpect(jsonPath(operation + ".responses['400']").exists())
                .andExpect(jsonPath(operation + ".responses['401']").exists())
                .andExpect(jsonPath(operation + ".responses['403']").exists())
                .andExpect(jsonPath(operation + ".responses['404']").exists())
                .andExpect(jsonPath(operation + ".responses['409']").exists())
                .andExpect(jsonPath(operation + ".security[*].accessTokenCookie").exists())
                .andExpect(
                        jsonPath(operation + ".parameters[?(@.name == 'X-XSRF-TOKEN')].required").value(hasItem(true))
                )
                .andExpect(jsonPath(operation + ".requestBody").doesNotExist())
                .andExpect(jsonPath(schema + ".properties.status.enum").value(contains("QUEUED")))
                .andExpect(
                        jsonPath(schema + ".required").value(
                                containsInAnyOrder(
                                        "jobId",
                                        "status",
                                        "attemptCount"
                                )
                        )
                );
    }

    @Test
    @DisplayName("녹음을 시작하지 않은 같은 Workspace 멤버도 실패 Job을 재시도할 수 있다")
    void retryDocumentGenerationJob_success_otherWorkspaceMember() throws Exception {
        // given
        long otherMemberId = fixtures.saveMember("다른 팀원");
        fixtures.join(
                workspaceId,
                otherMemberId
        );

        // when & then
        retry(
                workspaceId,
                otherMemberId,
                jobId
        ).andExpect(status().isAccepted())
                .andExpect(jsonPath("$.jobId").value(jobId))
                .andExpect(jsonPath("$.status").value("QUEUED"));
        assertThat(userRetryCount()).isEqualTo(1);
    }

    private void assertRetryDenied() throws Exception {
        String before = fixtures.snapshot();
        String jobBefore = jobSnapshot();
        retry(
                workspaceId,
                memberId,
                jobId
        ).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RETRY_NOT_ALLOWED"));
        assertThat(fixtures.snapshot()).isEqualTo(before);
        assertThat(jobSnapshot()).isEqualTo(jobBefore);
    }

    private ResultActions retry(
            long requestedWorkspaceId,
            long requestedMemberId,
            long requestedJobId
    ) throws Exception {
        return mvc.perform(
                post(
                        RETRY_PATH,
                        requestedWorkspaceId,
                        requestedJobId
                ).contentType(MediaType.APPLICATION_JSON)
                        .cookie(
                                cookie(requestedMemberId),
                                csrfCookie
                        )
                        .header(
                                "X-XSRF-TOKEN",
                                csrfCookie.getValue()
                        )
        );
    }

    private Cookie cookie(long requestedMemberId) {
        return new Cookie(
                "KNOT_ACCESS_TOKEN",
                tokens.issue(
                        AuthenticatedMember.of(
                                requestedMemberId,
                                "멤버",
                                null
                        )
                )
        );
    }

    private long userRetryCount() {
        return jdbc.sql("SELECT COALESCE(SUM(user_retry_count), 0) FROM document_generation_jobs")
                .query(Long.class)
                .single();
    }

    private String jobSnapshot() {
        return jdbc.sql("SELECT row_to_json(j)::text FROM document_generation_jobs j WHERE id = :id")
                .param(
                        "id",
                        jobId
                )
                .query(String.class)
                .single();
    }

    private String documentSnapshot(long documentId) {
        return jdbc.sql("SELECT row_to_json(d)::text FROM documents d WHERE id = :id")
                .param(
                        "id",
                        documentId
                )
                .query(String.class)
                .single();
    }
}
