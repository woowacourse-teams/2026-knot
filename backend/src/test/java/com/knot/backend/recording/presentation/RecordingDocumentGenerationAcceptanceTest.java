package com.knot.backend.recording.presentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
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
class RecordingDocumentGenerationAcceptanceTest {
    private static final String DETAIL_PATH = "/api/v1/workspaces/{workspaceId}/recordings/{recordingId}";
    private static final String RETRY_PATH = "/api/v1/workspaces/{workspaceId}/document-generation-jobs/{jobId}/retry";
    private static final Instant NOW = DocumentFixtures.CREATED_AT.plusSeconds(120);
    private static final String CSRF_COOKIE_NAME = "XSRF-TOKEN";
    private static final String JWT_COOKIE_NAME = "KNOT_ACCESS_TOKEN";

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

    @BeforeEach
    void setUp() throws Exception {
        when(clock.instant()).thenReturn(NOW);
        jdbc.sql("TRUNCATE TABLE members, workspaces RESTART IDENTITY CASCADE")
                .update();
        fixtures = new DocumentFixtures(jdbc);
        memberId = fixtures.saveMember("녹음 작성자");
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
        csrfCookie = mvc.perform(get("/api/v1/auth/csrf"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getCookie(CSRF_COOKIE_NAME);
        assertThat(csrfCookie).isNotNull();
    }

    @Test
    @DisplayName("문서 생성 배치가 없으면 단건 조회의 documentGeneration은 null이다")
    void findDetail_success_documentGenerationNullBeforeStarted() throws Exception {
        // when
        ResultActions result = findDetail(
                workspaceId,
                recordingId,
                memberId
        );

        // then
        result.andExpect(status().isOk())
                .andExpect(jsonPath("$.documentGeneration").value(nullValue()));
    }

    @Test
    @DisplayName("분류 단계 실패는 FAILED와 성공 수 0, 실패 Job ID로 조회된다")
    void findDetail_success_classificationFailure() throws Exception {
        // given
        long failedJobId = saveClassificationFailure(DocumentFixtures.CREATED_AT);

        // when
        ResultActions result = findDetail(
                workspaceId,
                recordingId,
                memberId
        );

        // then
        result.andExpect(status().isOk())
                .andExpect(jsonPath("$.documentGeneration.status").value("FAILED"))
                .andExpect(jsonPath("$.documentGeneration.succeededCount").value(0))
                .andExpect(jsonPath("$.documentGeneration.failedJobIds").isArray())
                .andExpect(jsonPath("$.documentGeneration.failedJobIds").value(contains(responseId(failedJobId))));
    }

    @Test
    @DisplayName("두 문서가 성공하고 하나가 대기 중이면 QUEUED라서 문서 이동 상태가 아니다")
    void findDetail_success_partialPending() throws Exception {
        // given
        fixtures.saveJob(
                transcriptId,
                "SUCCEEDED"
        );
        fixtures.saveJob(
                transcriptId,
                "SUCCEEDED"
        );
        fixtures.saveJob(
                transcriptId,
                "QUEUED"
        );

        // when
        ResultActions result = findDetail(
                workspaceId,
                recordingId,
                memberId
        );

        // then
        result.andExpect(status().isOk())
                .andExpect(jsonPath("$.documentGeneration.status").value("QUEUED"))
                .andExpect(jsonPath("$.documentGeneration.succeededCount").value(2))
                .andExpect(jsonPath("$.documentGeneration.failedJobIds").isArray())
                .andExpect(jsonPath("$.documentGeneration.failedJobIds").isEmpty());
    }

    @Test
    @DisplayName("두 문서가 성공하고 하나가 실패하면 FAILED와 성공 수 2, 실패 Job ID로 조회된다")
    void findDetail_success_partialFailure() throws Exception {
        // given
        fixtures.saveJob(
                transcriptId,
                "SUCCEEDED"
        );
        fixtures.saveJob(
                transcriptId,
                "SUCCEEDED"
        );
        long failedJobId = fixtures.saveJob(
                transcriptId,
                "FAILED"
        );

        // when
        ResultActions result = findDetail(
                workspaceId,
                recordingId,
                memberId
        );

        // then
        result.andExpect(status().isOk())
                .andExpect(jsonPath("$.documentGeneration.status").value("FAILED"))
                .andExpect(jsonPath("$.documentGeneration.succeededCount").value(2))
                .andExpect(jsonPath("$.documentGeneration.failedJobIds").isArray())
                .andExpect(jsonPath("$.documentGeneration.failedJobIds").value(contains(responseId(failedJobId))));
    }

    @Test
    @DisplayName("모든 문서가 성공하면 SUCCEEDED와 성공 수를 조회한다")
    void findDetail_success_allSucceeded() throws Exception {
        // given
        fixtures.saveJob(
                transcriptId,
                "SUCCEEDED"
        );
        fixtures.saveJob(
                transcriptId,
                "SUCCEEDED"
        );

        // when
        ResultActions result = findDetail(
                workspaceId,
                recordingId,
                memberId
        );

        // then
        result.andExpect(status().isOk())
                .andExpect(jsonPath("$.documentGeneration.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.documentGeneration.succeededCount").value(2))
                .andExpect(jsonPath("$.documentGeneration.failedJobIds").isArray())
                .andExpect(jsonPath("$.documentGeneration.failedJobIds").isEmpty());
    }

    @Test
    @DisplayName("정리할 내용이 없으면 NO_CONTENT와 성공 수 0을 조회한다")
    void findDetail_success_noContent() throws Exception {
        // given
        saveNoContentBatch();

        // when
        ResultActions result = findDetail(
                workspaceId,
                recordingId,
                memberId
        );

        // then
        result.andExpect(status().isOk())
                .andExpect(jsonPath("$.documentGeneration.status").value("NO_CONTENT"))
                .andExpect(jsonPath("$.documentGeneration.succeededCount").value(0))
                .andExpect(jsonPath("$.documentGeneration.failedJobIds").isArray())
                .andExpect(jsonPath("$.documentGeneration.failedJobIds").isEmpty());
    }

    @Test
    @DisplayName("한 녹음의 실패 Job이 여러 개이면 단건 조회에 모두 포함된다")
    void findDetail_success_multipleFailedJobIds() throws Exception {
        // given
        long firstJobId = fixtures.saveJob(
                transcriptId,
                "FAILED"
        );
        long secondJobId = fixtures.saveJob(
                transcriptId,
                "FAILED"
        );

        // when
        ResultActions result = findDetail(
                workspaceId,
                recordingId,
                memberId
        );

        // then
        result.andExpect(status().isOk())
                .andExpect(jsonPath("$.documentGeneration.status").value("FAILED"))
                .andExpect(jsonPath("$.documentGeneration.failedJobIds").isArray())
                .andExpect(
                        jsonPath("$.documentGeneration.failedJobIds").value(
                                containsInAnyOrder(
                                        responseId(firstJobId),
                                        responseId(secondJobId)
                                )
                        )
                );
    }

    @Test
    @DisplayName("재시도 202 뒤 단건 조회는 QUEUED이고 해당 실패 ID를 더 이상 노출하지 않는다")
    void findDetail_success_retryAccepted() throws Exception {
        // given
        long failedJobId = fixtures.saveJob(
                transcriptId,
                "FAILED"
        );
        retry(failedJobId).andExpect(status().isAccepted());

        // when
        ResultActions result = findDetail(
                workspaceId,
                recordingId,
                memberId
        );

        // then
        result.andExpect(status().isOk())
                .andExpect(jsonPath("$.documentGeneration.status").value("QUEUED"))
                .andExpect(jsonPath("$.documentGeneration.succeededCount").value(0))
                .andExpect(jsonPath("$.documentGeneration.failedJobIds").isArray())
                .andExpect(jsonPath("$.documentGeneration.failedJobIds").isEmpty());
    }

    @Test
    @DisplayName("마지막 실패 후 7일 표시 기한이 지나도 실패 Job ID를 기한 없이 모두 노출한다")
    void findDetail_success_failureIdIncludedAfterHomeDisplayPeriod() throws Exception {
        // given
        Instant failedAt = DocumentFixtures.CREATED_AT;
        long failedJobId = fixtures.saveJob(
                transcriptId,
                "FAILED",
                failedAt,
                failedAt
        );
        when(clock.instant()).thenReturn(failedAt.plusSeconds(30 * 24 * 60 * 60));

        // when
        ResultActions result = findDetail(
                workspaceId,
                recordingId,
                memberId
        );

        // then
        result.andExpect(status().isOk())
                .andExpect(jsonPath("$.documentGeneration.status").value("FAILED"))
                .andExpect(jsonPath("$.documentGeneration.succeededCount").value(0))
                .andExpect(jsonPath("$.documentGeneration.failedJobIds").value(contains(responseId(failedJobId))));
    }

    @Test
    @DisplayName("이전 정책으로 실패 Job 행이 정리되었어도 배치의 FAILED와 성공 수는 유지되고 실패 ID만 비어 있다")
    void findDetail_success_deletedFailureKeepsBatchSummary() throws Exception {
        // given
        fixtures.saveJob(
                transcriptId,
                "SUCCEEDED"
        );
        fixtures.saveJob(
                transcriptId,
                "SUCCEEDED"
        );
        long failedJobId = fixtures.saveJob(
                transcriptId,
                "FAILED"
        );
        deleteJob(failedJobId);

        // when
        ResultActions result = findDetail(
                workspaceId,
                recordingId,
                memberId
        );

        // then
        result.andExpect(status().isOk())
                .andExpect(jsonPath("$.documentGeneration.status").value("FAILED"))
                .andExpect(jsonPath("$.documentGeneration.succeededCount").value(2))
                .andExpect(jsonPath("$.documentGeneration.failedJobIds").isArray())
                .andExpect(jsonPath("$.documentGeneration.failedJobIds").isEmpty());
    }

    @Test
    @DisplayName("사용자 재시도를 3회 한 실패 Job도 ID에 포함되고 재시도 요청은 202다")
    void findDetail_success_retriedThreeTimesJobIncludedAndRetryable() throws Exception {
        // given
        long failedJobId = fixtures.saveJob(
                transcriptId,
                "FAILED"
        );
        retryThreeTimesByUser(failedJobId);
        ResultActions detail = findDetail(
                workspaceId,
                recordingId,
                memberId
        );

        // when
        ResultActions retry = retry(failedJobId);

        // then
        detail.andExpect(status().isOk())
                .andExpect(jsonPath("$.documentGeneration.failedJobIds").isArray())
                .andExpect(jsonPath("$.documentGeneration.failedJobIds").value(contains(responseId(failedJobId))));
        retry.andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("QUEUED"));
    }

    @Test
    @DisplayName("다른 녹음과 다른 Workspace의 실패 Job은 현재 녹음 단건 조회에서 제외된다")
    void findDetail_success_excludesOtherRecordingAndWorkspace() throws Exception {
        // given
        long currentFailedJobId = fixtures.saveJob(
                transcriptId,
                "FAILED"
        );
        long otherRecordingId = fixtures.saveRecording(
                workspaceId,
                memberId,
                60000
        );
        long otherTranscriptId = fixtures.saveTranscript(otherRecordingId);
        fixtures.saveJob(
                otherTranscriptId,
                "FAILED"
        );
        long otherWorkspaceId = fixtures.saveWorkspace();
        fixtures.join(
                otherWorkspaceId,
                memberId
        );
        long otherWorkspaceRecordingId = fixtures.saveRecording(
                otherWorkspaceId,
                memberId,
                60000
        );
        long otherWorkspaceTranscriptId = fixtures.saveTranscript(otherWorkspaceRecordingId);
        fixtures.saveJob(
                otherWorkspaceTranscriptId,
                "FAILED"
        );

        // when
        ResultActions result = findDetail(
                workspaceId,
                recordingId,
                memberId
        );

        // then
        result.andExpect(status().isOk())
                .andExpect(jsonPath("$.documentGeneration.status").value("FAILED"))
                .andExpect(jsonPath("$.documentGeneration.failedJobIds").isArray())
                .andExpect(
                        jsonPath("$.documentGeneration.failedJobIds").value(contains(responseId(currentFailedJobId)))
                );
    }

    @Test
    @DisplayName("OpenAPI는 녹음 단건 조회의 nullable 문서 생성 요약 계약을 공개한다")
    void openApi_success_documentGenerationContract() throws Exception {
        // given
        String detailSchema = "$.components.schemas.RecordingDetailResponse.properties.documentGeneration";
        String summarySchema = "$.components.schemas.RecordingDocumentGenerationResponse";

        // when
        ResultActions result = mvc.perform(get("/v3/api-docs"));

        // then
        result.andExpect(status().isOk())
                .andExpect(
                        jsonPath(detailSchema + ".type").value(
                                containsInAnyOrder(
                                        "object",
                                        "null"
                                )
                        )
                )
                .andExpect(
                        jsonPath(detailSchema + "['$ref']")
                                .value("#/components/schemas/RecordingDocumentGenerationResponse")
                )
                .andExpect(
                        jsonPath(summarySchema + ".properties.status.enum").value(
                                containsInAnyOrder(
                                        "QUEUED",
                                        "RUNNING",
                                        "SUCCEEDED",
                                        "FAILED",
                                        "NO_CONTENT"
                                )
                        )
                )
                .andExpect(jsonPath(summarySchema + ".properties.succeededCount.type").value("integer"))
                .andExpect(jsonPath(summarySchema + ".properties.failedJobIds.type").value("array"))
                .andExpect(jsonPath(summarySchema + ".properties.failedJobIds.items.type").value("integer"));
    }

    private ResultActions findDetail(
            long requestedWorkspaceId,
            long requestedRecordingId,
            long requestedMemberId
    ) throws Exception {
        return mvc.perform(
                get(
                        DETAIL_PATH,
                        requestedWorkspaceId,
                        requestedRecordingId
                ).cookie(cookie(requestedMemberId))
        );
    }

    private ResultActions retry(long jobId) throws Exception {
        return mvc.perform(
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
                                csrfCookie.getValue()
                        )
        );
    }

    private Cookie cookie(long requestedMemberId) {
        return new Cookie(
                JWT_COOKIE_NAME,
                tokens.issue(
                        AuthenticatedMember.of(
                                requestedMemberId,
                                "멤버",
                                null
                        )
                )
        );
    }

    private long saveClassificationFailure(Instant failedAt) {
        long batchId = jdbc.sql("""
                INSERT INTO document_generation_batches
                    (recording_session_id, transcript_id, topic_registration_state, accepted_at,
                        processing_status, finished_at)
                VALUES (:recordingId, :transcriptId, 'WAITING_CLASSIFICATION', :acceptedAt, 'FAILED', :failedAt)
                RETURNING id
                """)
                .param(
                        "recordingId",
                        recordingId
                )
                .param(
                        "transcriptId",
                        transcriptId
                )
                .param(
                        "acceptedAt",
                        Timestamp.from(failedAt)
                )
                .param(
                        "failedAt",
                        Timestamp.from(failedAt)
                )
                .query(Long.class)
                .single();
        return jdbc.sql("""
                INSERT INTO document_generation_jobs
                    (batch_id, stage, topic, transcript_id, status, created_at, updated_at,
                        last_failed_at, expires_at, failure_cause)
                VALUES (:batchId, 'CLASSIFICATION', NULL, :transcriptId, 'FAILED', :createdAt, :failedAt,
                    :failedAt, :expiresAt, 'LEGACY_FAILURE')
                RETURNING id
                """)
                .param(
                        "batchId",
                        batchId
                )
                .param(
                        "transcriptId",
                        transcriptId
                )
                .param(
                        "createdAt",
                        Timestamp.from(failedAt)
                )
                .param(
                        "failedAt",
                        Timestamp.from(failedAt)
                )
                .param(
                        "expiresAt",
                        Timestamp.from(failedAt.plusSeconds(7 * 24 * 60 * 60))
                )
                .query(Long.class)
                .single();
    }

    private void saveNoContentBatch() {
        jdbc.sql("""
                INSERT INTO document_generation_batches
                    (recording_session_id, transcript_id, topic_registration_state, accepted_at,
                        registered_at, cleanup_requested_at, processing_status, finished_at)
                VALUES (:recordingId, :transcriptId, 'NO_CONTENT', :time, :time, :time, 'NO_CONTENT', :time)
                """)
                .param(
                        "recordingId",
                        recordingId
                )
                .param(
                        "transcriptId",
                        transcriptId
                )
                .param(
                        "time",
                        Timestamp.from(DocumentFixtures.CREATED_AT)
                )
                .update();
    }

    private void deleteJob(long jobId) {
        jdbc.sql("DELETE FROM document_generation_jobs WHERE id = :id")
                .param(
                        "id",
                        jobId
                )
                .update();
    }

    private void retryThreeTimesByUser(long jobId) {
        jdbc.sql("""
                UPDATE document_generation_jobs
                SET user_retry_count = 3, attempt_count = 4
                WHERE id = :id
                """)
                .param(
                        "id",
                        jobId
                )
                .update();
    }

    private int responseId(long id) {
        return Math.toIntExact(id);
    }
}
