package com.knot.backend.recording.presentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.knot.backend.auth.domain.AuthTokenProvider;
import com.knot.backend.auth.domain.model.AuthenticatedMember;
import com.knot.backend.document.DocumentFixtures;
import com.knot.backend.testsupport.RecordingHistoryFixture;
import com.knot.backend.testsupport.TestApplicationProperties;
import com.knot.backend.testsupport.TestcontainersConfiguration;
import jakarta.servlet.http.Cookie;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestConstructor;
import org.springframework.test.context.TestConstructor.AutowireMode;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@Tag("acceptance")
@ActiveProfiles("dev")
@Import(TestcontainersConfiguration.class)
@TestApplicationProperties
@SpringBootTest
@AutoConfigureMockMvc
@TestConstructor(autowireMode = AutowireMode.ALL)
class RecordingDocumentAcceptanceTest {
    private static final String JWT_COOKIE_NAME = "KNOT_ACCESS_TOKEN";
    private static final String PATH = "/api/v1/workspaces/{workspaceId}/recordings/{recordingId}/document";
    private static final String DOCUMENTS_PATH = "/api/v1/workspaces/{workspaceId}/documents";
    private static final String OPEN_API_PATH = "$.paths['/api/v1/workspaces/{workspaceId}/recordings/{recordingId}/document'].get";
    private static final Instant STARTED_AT = Instant.parse("2026-10-22T01:30:00Z");

    private final MockMvc mockMvc;
    private final AuthTokenProvider authTokenProvider;
    private final JdbcClient jdbcClient;
    private final RecordingHistoryFixture fixture;
    private final DocumentFixtures documentFixtures;
    private long workspaceId;
    private long memberId;
    private long teammateId;

    RecordingDocumentAcceptanceTest(
            MockMvc mockMvc,
            AuthTokenProvider authTokenProvider,
            JdbcClient jdbcClient
    ) {
        this.mockMvc = mockMvc;
        this.authTokenProvider = authTokenProvider;
        this.jdbcClient = jdbcClient;
        this.fixture = new RecordingHistoryFixture(jdbcClient);
        this.documentFixtures = new DocumentFixtures(jdbcClient);
    }

    @BeforeEach
    void setUp() {
        fixture.clear();
        memberId = fixture.member("루덴스");
        teammateId = fixture.member("흑곰");
        workspaceId = fixture.workspace("녹음 팀");
        fixture.join(
                workspaceId,
                memberId
        );
        fixture.join(
                workspaceId,
                teammateId
        );
    }

    @Test
    @DisplayName("녹음을 시작하지 않은 멤버도 문서 일부 실패·요약 완료 녹음 문서를 받고 조회 전후 저장 상태가 같다")
    void find_success_teammateRecording() throws Exception {
        // given
        long recordingId = saved(
                teammateId,
                0
        );
        long transcriptId = documentFixtures.saveTranscript(recordingId);
        documentFixtures.saveJob(
                transcriptId,
                "SUCCEEDED"
        );
        Instant failedAt = Instant.now()
                .minus(Duration.ofDays(30));
        long failedJobId = documentFixtures.saveJob(
                transcriptId,
                "FAILED",
                failedAt,
                failedAt
        );
        fixture.title(
                recordingId,
                "회원 탈퇴 정책 논의"
        );
        fixture.meetingSummary(
                recordingId,
                transcriptId,
                "SUCCEEDED"
        );
        List<List<Map<String, Object>>> before = storedState();

        // when
        ResultActions result = find(
                workspaceId,
                String.valueOf(recordingId),
                memberId
        );

        // then
        result.andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(8))
                .andExpect(jsonPath("$.recordingId").value(recordingId))
                .andExpect(jsonPath("$.title").value("회원 탈퇴 정책 논의"))
                .andExpect(jsonPath("$.startedAt").value("2026-10-22T01:30:00Z"))
                .andExpect(jsonPath("$.recordingDurationSeconds").value(61))
                .andExpect(jsonPath("$.processingStatus").value("DOCUMENT_FAILED"))
                .andExpect(jsonPath("$.retryable").value(true))
                .andExpect(jsonPath("$.retryableJobIds.length()").value(1))
                .andExpect(jsonPath("$.retryableJobIds[0]").value(failedJobId))
                .andExpect(jsonPath("$.meetingSummary.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.meetingSummary.content").value(RecordingHistoryFixture.MEETING_SUMMARY))
                .andExpect(jsonPath("$.meetingSummary.retryable").value(false));
        assertThat(storedState()).isEqualTo(before);
    }

    @Test
    @DisplayName("전사 실패 녹음은 대체 이름과 null 요약을 준다")
    void find_success_transcriptionFailed() throws Exception {
        // given
        long recordingId = saved(
                memberId,
                0
        );
        fixture.transcriptionFailed(recordingId);

        // when
        ResultActions result = find(
                workspaceId,
                String.valueOf(recordingId),
                teammateId
        );

        // then
        result.andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(8))
                .andExpect(jsonPath("$.title").value("루덴스 님의 녹음"))
                .andExpect(jsonPath("$.processingStatus").value("TRANSCRIPTION_FAILED"))
                .andExpect(jsonPath("$.retryableJobIds").isEmpty())
                .andExpect(jsonPath("$.meetingSummary").value(nullValue()));
    }

    @Test
    @DisplayName("녹음 문서는 문서 목록과 확인이 필요한 문서에 나오지 않는다")
    void find_success_notTopicDocument() throws Exception {
        // given
        long recordingId = saved(
                teammateId,
                0
        );
        long transcriptId = documentFixtures.saveTranscript(recordingId);
        long documentId = documentFixtures.saveDocument(
                workspaceId,
                recordingId,
                transcriptId,
                documentFixtures.saveJob(
                        transcriptId,
                        "SUCCEEDED"
                ),
                "정책"
        );
        documentFixtures.target(
                documentId,
                memberId,
                null
        );
        fixture.meetingSummary(
                recordingId,
                transcriptId,
                "SUCCEEDED"
        );
        find(
                workspaceId,
                String.valueOf(recordingId),
                memberId
        ).andExpect(status().isOk());

        // when
        ResultActions documents = documents(null);
        ResultActions pending = documents("PENDING");

        // then
        documents.andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].id").value(documentId));
        pending.andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].id").value(documentId));
    }

    @Test
    @DisplayName("다른 Workspace·녹음 중·폐기·음성 저장 실패·없는 녹음은 404 RECORDING_NOT_FOUND다")
    void find_fail_recordingNotFound() throws Exception {
        // given
        long otherWorkspaceId = fixture.workspace("다른 팀");
        fixture.join(
                otherWorkspaceId,
                memberId
        );
        long otherWorkspace = fixture.savedRecording(
                otherWorkspaceId,
                memberId,
                STARTED_AT
        );
        long recording = saved(
                teammateId,
                1
        );
        fixture.changeStatus(
                recording,
                "RECORDING"
        );
        long discarded = saved(
                memberId,
                2
        );
        fixture.changeStatus(
                discarded,
                "DISCARDED"
        );
        long saveFailed = fixture.endedRecording(
                workspaceId,
                memberId,
                STARTED_AT.plusSeconds(3),
                "PCM16_CHUNKS_V1"
        );
        fixture.finalization(
                saveFailed,
                "FAILED"
        );

        // when & then
        for (long recordingId : List.of(
                otherWorkspace,
                recording,
                discarded,
                saveFailed,
                Long.MAX_VALUE
        )) {
            find(
                    workspaceId,
                    String.valueOf(recordingId),
                    memberId
            ).andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("RECORDING_NOT_FOUND"));
        }
    }

    @Test
    @DisplayName("현재 Workspace 멤버가 아니면 403 WORKSPACE_ACCESS_DENIED다")
    void find_fail_notMember() throws Exception {
        // given
        long outsiderId = fixture.member("외부인");
        long recordingId = saved(
                memberId,
                0
        );

        // when
        ResultActions result = find(
                workspaceId,
                String.valueOf(recordingId),
                outsiderId
        );

        // then
        result.andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("WORKSPACE_ACCESS_DENIED"));
    }

    @ParameterizedTest
    @CsvSource({"true,0,INVALID_RECORDING_DATA", "true,invalid,INVALID_PARAMETER", "false,1,INVALID_WORKSPACE_ID"})
    @DisplayName("양수가 아닌 ID와 형식 오류는 400이다")
    void find_fail_invalidPath(
            boolean validWorkspace,
            String recordingId,
            String errorCode
    ) throws Exception {
        // given
        long requestedWorkspaceId = 0;
        if (validWorkspace) {
            requestedWorkspaceId = workspaceId;
        }

        // when
        ResultActions result = find(
                requestedWorkspaceId,
                recordingId,
                memberId
        );

        // then
        result.andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(errorCode));
    }

    @Test
    @DisplayName("로그인하지 않으면 401이다")
    void find_fail_unauthenticated() throws Exception {
        // when
        ResultActions result = mockMvc.perform(
                get(
                        PATH,
                        workspaceId,
                        1
                )
        );

        // then
        result.andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("OpenAPI JSON에 녹음 문서 조회 계약을 공개한다")
    void find_success_openApi() throws Exception {
        // when
        ResultActions result = mockMvc.perform(get("/v3/api-docs"));

        // then
        result.andExpect(status().isOk())
                .andExpect(jsonPath(OPEN_API_PATH + ".responses['200']").exists())
                .andExpect(
                        jsonPath(OPEN_API_PATH + ".responses['404'].description")
                                .value(containsString("RECORDING_NOT_FOUND"))
                )
                .andExpect(
                        jsonPath("$.components.schemas.RecordingDocumentResponse.required").value(
                                hasItems(
                                        "recordingId",
                                        "title",
                                        "startedAt",
                                        "recordingDurationSeconds",
                                        "processingStatus",
                                        "retryable",
                                        "retryableJobIds",
                                        "meetingSummary"
                                )
                        )
                )
                .andExpect(
                        jsonPath("$.components.schemas.RecordingMeetingSummaryResponse.required").value(
                                hasItems(
                                        "status",
                                        "content",
                                        "retryable"
                                )
                        )
                );
    }

    private long saved(
            long starterId,
            int secondsAfterStart
    ) {
        return fixture.savedRecording(
                workspaceId,
                starterId,
                STARTED_AT.plusSeconds(secondsAfterStart)
        );
    }

    private ResultActions find(
            long requestedWorkspaceId,
            String recordingId,
            long requesterId
    ) throws Exception {
        return mockMvc.perform(
                get(
                        PATH,
                        requestedWorkspaceId,
                        recordingId
                ).cookie(accessTokenCookie(requesterId))
        );
    }

    private ResultActions documents(String myConfirmation) throws Exception {
        if (myConfirmation == null) {
            return mockMvc.perform(
                    get(
                            DOCUMENTS_PATH,
                            workspaceId
                    ).cookie(accessTokenCookie(memberId))
            );
        }
        return mockMvc.perform(
                get(
                        DOCUMENTS_PATH,
                        workspaceId
                ).param(
                        "myConfirmation",
                        myConfirmation
                )
                        .cookie(accessTokenCookie(memberId))
        );
    }

    private List<List<Map<String, Object>>> storedState() {
        return List.of(
                rows("SELECT * FROM recording_sessions ORDER BY id"),
                rows("SELECT * FROM recording_audio_uploads ORDER BY id"),
                rows("SELECT * FROM recording_transcriptions ORDER BY id"),
                rows("SELECT * FROM recording_title_generations ORDER BY id"),
                rows("SELECT * FROM recording_meeting_summaries ORDER BY id"),
                rows("SELECT * FROM transcripts ORDER BY id"),
                rows("SELECT * FROM document_generation_batches ORDER BY id"),
                rows("SELECT * FROM document_generation_jobs ORDER BY id"),
                rows("SELECT * FROM documents ORDER BY id"),
                rows("SELECT * FROM document_confirmations ORDER BY document_id, member_id")
        );
    }

    private List<Map<String, Object>> rows(String sql) {
        return jdbcClient.sql(sql)
                .query()
                .listOfRows();
    }

    private Cookie accessTokenCookie(long requesterId) {
        return new Cookie(
                JWT_COOKIE_NAME,
                authTokenProvider.issue(
                        AuthenticatedMember.of(
                                requesterId,
                                "member",
                                null
                        )
                )
        );
    }
}
