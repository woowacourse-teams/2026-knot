package com.knot.backend.recording.presentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItems;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.knot.backend.auth.domain.AuthTokenProvider;
import com.knot.backend.auth.domain.model.AuthenticatedMember;
import com.knot.backend.document.DocumentFixtures;
import com.knot.backend.recording.domain.model.TranscriptSegment;
import com.knot.backend.recording.domain.repository.TranscriptSegmentRepository;
import com.knot.backend.testsupport.RecordingHistoryFixture;
import com.knot.backend.testsupport.TestApplicationProperties;
import com.knot.backend.testsupport.TestcontainersConfiguration;
import jakarta.servlet.http.Cookie;
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
class RecordingTranscriptAcceptanceTest {
    private static final String JWT_COOKIE_NAME = "KNOT_ACCESS_TOKEN";
    private static final String PATH = "/api/v1/workspaces/{workspaceId}/recordings/{recordingId}/transcript";
    private static final String DOCUMENT_TRANSCRIPT_PATH = "/api/v1/workspaces/{workspaceId}/documents/{documentId}/transcript";
    private static final Instant STARTED_AT = Instant.parse("2026-10-22T01:30:00Z");

    private final MockMvc mockMvc;
    private final AuthTokenProvider authTokenProvider;
    private final TranscriptSegmentRepository transcriptSegmentRepository;
    private final JdbcClient jdbcClient;
    private final RecordingHistoryFixture fixture;
    private final DocumentFixtures documentFixtures;
    private long workspaceId;
    private long memberId;
    private long teammateId;

    RecordingTranscriptAcceptanceTest(
            MockMvc mockMvc,
            AuthTokenProvider authTokenProvider,
            TranscriptSegmentRepository transcriptSegmentRepository,
            JdbcClient jdbcClient
    ) {
        this.mockMvc = mockMvc;
        this.authTokenProvider = authTokenProvider;
        this.transcriptSegmentRepository = transcriptSegmentRepository;
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
    @DisplayName("녹음을 시작하지 않은 멤버도 같은 녹음의 문서 원문과 같은 전사 ID·구간을 받고 상태는 바뀌지 않는다")
    void find_success_sameAsDocumentTranscript() throws Exception {
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
        transcriptSegmentRepository.saveAll(
                List.of(
                        TranscriptSegment.create(
                                transcriptId,
                                1,
                                160000,
                                null,
                                null,
                                "두 번째"
                        ),
                        TranscriptSegment.create(
                                transcriptId,
                                0,
                                0,
                                4000L,
                                1,
                                "첫 번째"
                        )
                )
        );
        List<List<Map<String, Object>>> before = storedState();

        // when
        ResultActions result = find(
                workspaceId,
                String.valueOf(recordingId),
                memberId
        );
        ResultActions documentResult = mockMvc.perform(
                get(
                        DOCUMENT_TRANSCRIPT_PATH,
                        workspaceId,
                        documentId
                ).cookie(accessTokenCookie(memberId))
        );

        // then
        documentResult.andExpect(status().isOk());
        result.andExpect(status().isOk())
                .andExpect(jsonPath("$.recordingId").value(recordingId))
                .andExpect(jsonPath("$.transcriptId").value(transcriptId))
                .andExpect(jsonPath("$.transcriptText").value("전체 녹음 원문"))
                .andExpect(jsonPath("$.segments[0].text").value("첫 번째"))
                .andExpect(jsonPath("$.segments[1].text").value("두 번째"))
                .andExpect(jsonPath("$.status").doesNotExist());
        String documentTranscript = body(documentResult);
        String recordingTranscript = body(result);
        assertThat(
                (Object) JsonPath.read(
                        recordingTranscript,
                        "$.transcriptId"
                )
        ).isEqualTo(
                JsonPath.read(
                        documentTranscript,
                        "$.transcriptId"
                )
        );
        assertThat(
                (Object) JsonPath.read(
                        recordingTranscript,
                        "$.segments"
                )
        ).isEqualTo(
                JsonPath.read(
                        documentTranscript,
                        "$.segments"
                )
        );
        assertThat(storedState()).isEqualTo(before);
    }

    @Test
    @DisplayName("다른 Workspace·녹음 중·폐기·음성 저장 실패 녹음은 404 RECORDING_NOT_FOUND다")
    void find_fail_recordingNotFound() throws Exception {
        // given
        long otherWorkspaceId = fixture.workspace("다른 팀");
        fixture.join(
                otherWorkspaceId,
                memberId
        );
        long otherWorkspace = withTranscript(
                fixture.savedRecording(
                        otherWorkspaceId,
                        memberId,
                        STARTED_AT
                )
        );
        long recording = withTranscript(
                saved(
                        teammateId,
                        1
                )
        );
        fixture.changeStatus(
                recording,
                "RECORDING"
        );
        long discarded = withTranscript(
                saved(
                        memberId,
                        2
                )
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
                saveFailed
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
    @DisplayName("전사 전·전사 실패·NO_CONTENT 녹음은 404 RECORDING_TRANSCRIPT_NOT_FOUND다")
    void find_fail_transcriptNotFound() throws Exception {
        // given
        long beforeTranscription = saved(
                teammateId,
                0
        );
        long transcriptionFailed = saved(
                teammateId,
                1
        );
        fixture.transcriptionFailed(transcriptionFailed);
        long noContent = saved(
                teammateId,
                2
        );
        fixture.batch(
                noContent,
                "NO_CONTENT",
                "NO_CONTENT"
        );

        // when & then
        for (long recordingId : List.of(
                beforeTranscription,
                transcriptionFailed,
                noContent
        )) {
            find(
                    workspaceId,
                    String.valueOf(recordingId),
                    memberId
            ).andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("RECORDING_TRANSCRIPT_NOT_FOUND"));
        }
    }

    @Test
    @DisplayName("현재 Workspace 멤버가 아니면 403 WORKSPACE_ACCESS_DENIED다")
    void find_fail_notMember() throws Exception {
        // given
        long outsiderId = fixture.member("외부인");
        long recordingId = withTranscript(
                saved(
                        memberId,
                        0
                )
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
    @DisplayName("OpenAPI JSON에 녹음 전사 조회 계약을 공개한다")
    void find_success_openApi() throws Exception {
        // when
        ResultActions result = mockMvc.perform(get("/v3/api-docs"));

        // then
        result.andExpect(status().isOk())
                .andExpect(
                        jsonPath(
                                "$.paths['/api/v1/workspaces/{workspaceId}/recordings/{recordingId}/transcript'].get.responses['200']"
                        ).exists()
                )
                .andExpect(
                        jsonPath(
                                "$.paths['/api/v1/workspaces/{workspaceId}/recordings/{recordingId}/transcript'].get.responses['404'].description"
                        ).value(containsString("RECORDING_TRANSCRIPT_NOT_FOUND"))
                )
                .andExpect(
                        jsonPath("$.components.schemas.RecordingTranscriptResponse.required").value(
                                hasItems(
                                        "recordingId",
                                        "transcriptId",
                                        "transcriptText",
                                        "segments"
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

    private long withTranscript(long recordingId) {
        fixture.batch(
                recordingId,
                "TOPICS_REGISTERED",
                "SUCCEEDED"
        );
        return recordingId;
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

    private String body(ResultActions result) throws Exception {
        return result.andReturn()
                .getResponse()
                .getContentAsString();
    }

    private List<List<Map<String, Object>>> storedState() {
        return List.of(
                rows("SELECT * FROM recording_sessions ORDER BY id"),
                rows("SELECT * FROM transcripts ORDER BY id"),
                rows("SELECT * FROM transcript_segments ORDER BY id"),
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
