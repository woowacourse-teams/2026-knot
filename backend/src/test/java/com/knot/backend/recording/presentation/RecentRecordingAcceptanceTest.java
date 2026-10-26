package com.knot.backend.recording.presentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.knot.backend.auth.domain.AuthTokenProvider;
import com.knot.backend.auth.domain.model.AuthenticatedMember;
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
import org.junit.jupiter.params.provider.ValueSource;
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
class RecentRecordingAcceptanceTest {
    private static final String JWT_COOKIE_NAME = "KNOT_ACCESS_TOKEN";
    private static final String PATH = "/api/v1/workspaces/{workspaceId}/recordings";
    private static final Instant STARTED_AT = Instant.parse("2026-10-22T01:30:00Z");

    private final MockMvc mockMvc;
    private final AuthTokenProvider authTokenProvider;
    private final JdbcClient jdbcClient;
    private final RecordingHistoryFixture fixture;
    private long workspaceId;
    private long memberId;
    private long teammateId;

    RecentRecordingAcceptanceTest(
            MockMvc mockMvc,
            AuthTokenProvider authTokenProvider,
            JdbcClient jdbcClient
    ) {
        this.mockMvc = mockMvc;
        this.authTokenProvider = authTokenProvider;
        this.jdbcClient = jdbcClient;
        this.fixture = new RecordingHistoryFixture(jdbcClient);
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
    @DisplayName("팀원이 시작한 녹음을 AI 제목 또는 대체 이름, 처리 상태와 함께 최신순으로 받는다")
    void find_success() throws Exception {
        // given
        long titled = fixture.savedRecording(
                workspaceId,
                teammateId,
                STARTED_AT
        );
        fixture.batch(
                titled,
                "TOPICS_REGISTERED",
                "SUCCEEDED"
        );
        fixture.title(
                titled,
                "회원 탈퇴 정책 논의"
        );
        long processing = fixture.savedRecording(
                workspaceId,
                teammateId,
                STARTED_AT.plusSeconds(60)
        );

        // when
        ResultActions result = find(
                memberId,
                Map.of()
        );

        // then
        result.andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].recordingId").value(processing))
                .andExpect(jsonPath("$.items[0].title").value("흑곰 님의 녹음"))
                .andExpect(jsonPath("$.items[0].startedAt").value("2026-10-22T01:31:00Z"))
                .andExpect(jsonPath("$.items[0].recordingDurationSeconds").value(61))
                .andExpect(jsonPath("$.items[0].processingStatus").value("PROCESSING"))
                .andExpect(jsonPath("$.items[0].retryable").value(false))
                .andExpect(jsonPath("$.items[1].recordingId").value(titled))
                .andExpect(jsonPath("$.items[1].title").value("회원 탈퇴 정책 논의"))
                .andExpect(jsonPath("$.items[1].processingStatus").value("COMPLETED"))
                .andExpect(jsonPath("$.items[1].retryable").value(false))
                .andExpect(jsonPath("$.nextCursor").doesNotExist());
    }

    @Test
    @DisplayName("다음 커서로 이어 받은 페이지는 앞 페이지와 겹치지 않는다")
    void find_success_nextPage() throws Exception {
        // given
        long older = fixture.savedRecording(
                workspaceId,
                memberId,
                STARTED_AT
        );
        long newer = fixture.savedRecording(
                workspaceId,
                memberId,
                STARTED_AT
        );
        String cursor = jsonValue(
                find(
                        memberId,
                        Map.of(
                                "size",
                                "1"
                        )
                ).andExpect(jsonPath("$.items[0].recordingId").value(newer)),
                "nextCursor"
        );

        // when
        ResultActions result = find(
                memberId,
                Map.of(
                        "size",
                        "1",
                        "cursor",
                        cursor
                )
        );

        // then
        result.andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].recordingId").value(older))
                .andExpect(jsonPath("$.nextCursor").doesNotExist());
    }

    @Test
    @DisplayName("조회는 녹음·업로드·전사·문서 정리·제목 상태를 바꾸지 않는다")
    void find_success_readOnly() throws Exception {
        // given
        long recordingId = fixture.savedRecording(
                workspaceId,
                memberId,
                STARTED_AT
        );
        fixture.failedJob(
                fixture.batch(
                        recordingId,
                        "TOPICS_REGISTERED",
                        "FAILED"
                )
        );
        fixture.title(
                recordingId,
                "회원 탈퇴 정책 논의"
        );
        List<List<Map<String, Object>>> before = storedState();

        // when
        find(
                teammateId,
                Map.of()
        ).andExpect(status().isOk());

        // then
        assertThat(storedState()).isEqualTo(before);
    }

    @ParameterizedTest
    @ValueSource(strings = {"size=0", "size=101", "cursor=", "cursor=bm90LWEtY3Vyc29y"})
    @DisplayName("범위 밖 size와 빈·해석할 수 없는 cursor는 400 INVALID_PARAMETER다")
    void find_fail_invalidParameter(String query) throws Exception {
        // given
        String[] pair = query.split(
                "=",
                -1
        );

        // when
        ResultActions result = find(
                memberId,
                Map.of(
                        pair[0],
                        pair[1]
                )
        );

        // then
        result.andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"));
    }

    @Test
    @DisplayName("다른 Workspace 멤버는 403 WORKSPACE_ACCESS_DENIED다")
    void find_fail_notMember() throws Exception {
        // given
        long outsiderId = fixture.member("외부인");
        fixture.savedRecording(
                workspaceId,
                memberId,
                STARTED_AT
        );

        // when
        ResultActions result = find(
                outsiderId,
                Map.of()
        );

        // then
        result.andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("WORKSPACE_ACCESS_DENIED"));
    }

    @Test
    @DisplayName("로그인하지 않으면 401이다")
    void find_fail_unauthenticated() throws Exception {
        // when
        ResultActions result = mockMvc.perform(
                get(
                        PATH,
                        workspaceId
                )
        );

        // then
        result.andExpect(status().isUnauthorized());
    }

    private ResultActions find(
            long requesterId,
            Map<String, String> parameters
    ) throws Exception {
        var request = get(
                PATH,
                workspaceId
        ).cookie(accessTokenCookie(requesterId));
        parameters.forEach(request::param);
        return mockMvc.perform(request);
    }

    private String jsonValue(
            ResultActions result,
            String field
    ) throws Exception {
        String body = result.andReturn()
                .getResponse()
                .getContentAsString();
        return com.jayway.jsonpath.JsonPath.read(
                body,
                "$." + field
        );
    }

    private List<List<Map<String, Object>>> storedState() {
        return List.of(
                rows("SELECT * FROM recording_sessions ORDER BY id"),
                rows("SELECT * FROM recording_audio_uploads ORDER BY id"),
                rows("SELECT * FROM transcripts ORDER BY id"),
                rows("SELECT * FROM document_generation_batches ORDER BY id"),
                rows("SELECT * FROM document_generation_jobs ORDER BY id"),
                rows("SELECT * FROM recording_title_generations ORDER BY id")
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
