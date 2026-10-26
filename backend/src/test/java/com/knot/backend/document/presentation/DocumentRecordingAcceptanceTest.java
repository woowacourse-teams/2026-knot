package com.knot.backend.document.presentation;

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
class DocumentRecordingAcceptanceTest {
    private static final String JWT_COOKIE_NAME = "KNOT_ACCESS_TOKEN";
    private static final String PATH = "/api/v1/workspaces/{workspaceId}/document-recordings";
    private static final Instant STARTED_AT = Instant.parse("2026-10-22T01:30:00Z");
    private static final Instant CREATED_AT = Instant.parse("2026-10-22T02:00:00Z");

    private final MockMvc mockMvc;
    private final AuthTokenProvider authTokenProvider;
    private final JdbcClient jdbcClient;
    private final RecordingHistoryFixture fixture;
    private long workspaceId;
    private long memberId;
    private long teammateId;

    DocumentRecordingAcceptanceTest(
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
    @DisplayName("팀원이 시작한 녹음도 문서가 있으면 녹음 필드와 문서 항목을 묶어 받는다")
    void find_success() throws Exception {
        // given
        long recordingId = fixture.savedRecording(
                workspaceId,
                teammateId,
                STARTED_AT
        );
        long batchId = registered(recordingId);
        fixture.title(
                recordingId,
                "회원 탈퇴 정책 논의"
        );
        long first = fixture.document(
                batchId,
                "탈퇴 데이터 보존 범위",
                "탈퇴 후 30일간 데이터를 보관하기로 했다.",
                CREATED_AT
        );
        long second = fixture.document(
                batchId,
                "탈퇴 안내 문구",
                null,
                CREATED_AT.plusSeconds(1)
        );
        registered(
                fixture.savedRecording(
                        workspaceId,
                        memberId,
                        STARTED_AT.plusSeconds(60)
                )
        );

        // when
        ResultActions result = find(
                memberId,
                Map.of()
        );

        // then
        result.andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].recordingId").value(recordingId))
                .andExpect(jsonPath("$.items[0].title").value("회원 탈퇴 정책 논의"))
                .andExpect(jsonPath("$.items[0].startedAt").value("2026-10-22T01:30:00Z"))
                .andExpect(jsonPath("$.items[0].recordingDurationSeconds").value(61))
                .andExpect(jsonPath("$.items[0].documentCount").value(2))
                .andExpect(jsonPath("$.items[0].documents[0].id").value(first))
                .andExpect(jsonPath("$.items[0].documents[0].title").value("탈퇴 데이터 보존 범위"))
                .andExpect(jsonPath("$.items[0].documents[0].summary").value("탈퇴 후 30일간 데이터를 보관하기로 했다."))
                .andExpect(jsonPath("$.items[0].documents[1].id").value(second))
                .andExpect(jsonPath("$.items[0].documents[1].summary").isEmpty())
                .andExpect(jsonPath("$.nextCursor").isEmpty());
    }

    @Test
    @DisplayName("조회는 녹음·문서·확인·제목 상태를 바꾸지 않는다")
    void find_success_readOnly() throws Exception {
        // given
        long recordingId = fixture.savedRecording(
                workspaceId,
                memberId,
                STARTED_AT
        );
        fixture.document(
                registered(recordingId),
                "회원 탈퇴 정책",
                "탈퇴 정책을 정했다.",
                CREATED_AT
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
    @ValueSource(strings = {"", "bm90LWEtY3Vyc29y"})
    @DisplayName("빈 문자열이거나 해석할 수 없는 cursor는 400 INVALID_PARAMETER다")
    void find_fail_invalidCursor(String cursor) throws Exception {
        // when
        ResultActions result = find(
                memberId,
                Map.of(
                        "cursor",
                        cursor
                )
        );

        // then
        result.andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"));
    }

    @Test
    @DisplayName("현재 Workspace 멤버가 아니면 403 WORKSPACE_ACCESS_DENIED다")
    void find_fail_notMember() throws Exception {
        // given
        long outsiderId = fixture.member("외부인");

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

    private long registered(long recordingId) {
        return fixture.batch(
                recordingId,
                "TOPICS_REGISTERED",
                "SUCCEEDED"
        );
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

    private List<List<Map<String, Object>>> storedState() {
        return List.of(
                rows("SELECT * FROM recording_sessions ORDER BY id"),
                rows("SELECT * FROM transcripts ORDER BY id"),
                rows("SELECT * FROM document_generation_batches ORDER BY id"),
                rows("SELECT * FROM document_generation_jobs ORDER BY id"),
                rows("SELECT * FROM documents ORDER BY id"),
                rows("SELECT * FROM document_confirmations ORDER BY document_id, member_id"),
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
