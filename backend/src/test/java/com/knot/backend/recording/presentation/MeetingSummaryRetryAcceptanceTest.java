package com.knot.backend.recording.presentation;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.knot.backend.auth.domain.AuthTokenProvider;
import com.knot.backend.auth.domain.model.AuthenticatedMember;
import com.knot.backend.recording.application.MeetingSummaryGenerationService;
import com.knot.backend.recording.application.dto.result.MeetingSummaryTask;
import com.knot.backend.testsupport.RecordingHistoryFixture;
import com.knot.backend.testsupport.TestApplicationProperties;
import com.knot.backend.testsupport.TestcontainersConfiguration;
import jakarta.servlet.http.Cookie;
import java.time.Clock;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestConstructor;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import static org.mockito.Mockito.when;

@Tag("acceptance")
@ActiveProfiles("dev")
@Import(TestcontainersConfiguration.class)
@TestApplicationProperties
@SpringBootTest
@AutoConfigureMockMvc
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class MeetingSummaryRetryAcceptanceTest {
    private static final String PATH = "/api/v1/workspaces/{workspaceId}/recordings/{recordingId}/meeting-summary/retry";
    private static final Instant NOW = Instant.parse("2026-10-25T10:00:00Z");

    private final MockMvc mvc;
    private final AuthTokenProvider tokens;
    private final JdbcClient jdbc;
    private final MeetingSummaryGenerationService summaries;
    private final RecordingHistoryFixture fixture;
    @MockitoBean
    private Clock clock;
    private Cookie csrfCookie;
    private long member;
    private long teammate;
    private long outsider;
    private long workspace;
    private long recording;

    MeetingSummaryRetryAcceptanceTest(
            MockMvc mvc,
            AuthTokenProvider tokens,
            JdbcClient jdbc,
            MeetingSummaryGenerationService summaries
    ) {
        this.mvc = mvc;
        this.tokens = tokens;
        this.jdbc = jdbc;
        this.summaries = summaries;
        fixture = new RecordingHistoryFixture(jdbc);
    }

    @BeforeEach
    void setUp() throws Exception {
        fixture.clear();
        jdbc.sql("TRUNCATE recording_meeting_summary_activation")
                .update();
        when(clock.instant()).thenReturn(NOW);
        member = fixture.member("흑곰");
        teammate = fixture.member("이스타");
        outsider = fixture.member("외부인");
        workspace = fixture.workspace("요약 팀");
        fixture.join(
                workspace,
                member
        );
        fixture.join(
                workspace,
                teammate
        );
        summaries.registerEligible();
        recording = fixture.savedRecording(
                workspace,
                member,
                NOW
        );
        fixture.batch(
                recording,
                "TOPICS_REGISTERED",
                "FAILED"
        );
        summaries.registerEligible();
        MeetingSummaryTask task = summaries.claim(recording)
                .orElseThrow();
        summaries.fail(
                task,
                "INVALID_RESPONSE",
                false
        );
        csrfCookie = mvc.perform(get("/api/v1/auth/csrf"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getCookie("XSRF-TOKEN");
    }

    @Test
    @DisplayName("녹음을 시작하지 않은 현재 멤버가 요약만 202로 재접수한다")
    void retry_success_teammate() throws Exception {
        // when & then
        mvc.perform(
                post(
                        PATH,
                        workspace,
                        recording
                ).cookie(cookie(teammate))
                        .cookie(csrfCookie)
                        .header(
                                "X-XSRF-TOKEN",
                                csrfCookie.getValue()
                        )
        )
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.recordingId").value(recording))
                .andExpect(jsonPath("$.meetingSummaryStatus").value("QUEUED"));
        mvc.perform(
                post(
                        PATH,
                        workspace,
                        recording
                ).cookie(cookie(teammate))
                        .cookie(csrfCookie)
                        .header(
                                "X-XSRF-TOKEN",
                                csrfCookie.getValue()
                        )
        )
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MEETING_SUMMARY_RETRY_NOT_ALLOWED"));
    }

    @Test
    @DisplayName("현재 Workspace 멤버가 아니면 재접수를 거절한다")
    void retry_failure_nonMember() throws Exception {
        // when & then
        mvc.perform(
                post(
                        PATH,
                        workspace,
                        recording
                ).cookie(cookie(outsider))
                        .cookie(csrfCookie)
                        .header(
                                "X-XSRF-TOKEN",
                                csrfCookie.getValue()
                        )
        )
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("WORKSPACE_ACCESS_DENIED"));
    }

    @Test
    @DisplayName("인증과 CSRF 검증을 실제 보안 필터에서 적용한다")
    void retry_failure_security() throws Exception {
        // when & then
        mvc.perform(
                post(
                        PATH,
                        workspace,
                        recording
                ).cookie(csrfCookie)
                        .header(
                                "X-XSRF-TOKEN",
                                csrfCookie.getValue()
                        )
        )
                .andExpect(status().isUnauthorized());
        mvc.perform(
                post(
                        PATH,
                        workspace,
                        recording
                ).cookie(cookie(teammate))
        )
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CSRF_INVALID"));
    }

    @Test
    @DisplayName("다른 Workspace의 녹음은 없는 녹음으로 응답한다")
    void retry_failure_otherWorkspace() throws Exception {
        // given
        long other = fixture.workspace("다른 팀");
        fixture.join(
                other,
                teammate
        );
        // when & then
        mvc.perform(
                post(
                        PATH,
                        other,
                        recording
                ).cookie(cookie(teammate))
                        .cookie(csrfCookie)
                        .header(
                                "X-XSRF-TOKEN",
                                csrfCookie.getValue()
                        )
        )
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RECORDING_NOT_FOUND"));
    }

    @Test
    @DisplayName("요약이 없는 NO_CONTENT 녹음은 409로 응답한다")
    void retry_failure_noContent() throws Exception {
        // given
        long empty = fixture.savedRecording(
                workspace,
                member,
                NOW
        );
        fixture.batch(
                empty,
                "NO_CONTENT",
                "NO_CONTENT"
        );
        // when & then
        mvc.perform(
                post(
                        PATH,
                        workspace,
                        empty
                ).cookie(cookie(teammate))
                        .cookie(csrfCookie)
                        .header(
                                "X-XSRF-TOKEN",
                                csrfCookie.getValue()
                        )
        )
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MEETING_SUMMARY_RETRY_NOT_ALLOWED"));
    }

    @Test
    @DisplayName("음성 저장 실패 녹음은 녹음 문서와 같은 404를 반환한다")
    void retry_failure_unreadableRecording() throws Exception {
        // given
        jdbc.sql("UPDATE recording_audio_uploads SET status='RESERVED',completed_at=NULL WHERE recording_id=:id")
                .param(
                        "id",
                        recording
                )
                .update();
        // when & then
        mvc.perform(
                post(
                        PATH,
                        workspace,
                        recording
                ).cookie(cookie(teammate))
                        .cookie(csrfCookie)
                        .header(
                                "X-XSRF-TOKEN",
                                csrfCookie.getValue()
                        )
        )
                .andExpect(status().isNotFound());
    }

    private Cookie cookie(long id) {
        return new Cookie(
                "KNOT_ACCESS_TOKEN",
                tokens.issue(
                        AuthenticatedMember.of(
                                id,
                                "member",
                                null
                        )
                )
        );
    }
}
