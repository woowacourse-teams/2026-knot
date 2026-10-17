package com.knot.backend.workspace.presentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import ch.qos.logback.classic.spi.ILoggingEvent;
import com.knot.backend.auth.domain.AuthTokenProvider;
import com.knot.backend.auth.domain.model.AuthenticatedMember;
import com.knot.backend.global.logging.RequestIdFilter;
import com.knot.backend.testsupport.LogCapture;
import com.knot.backend.testsupport.TestApplicationProperties;
import com.knot.backend.testsupport.TestcontainersConfiguration;
import com.knot.backend.workspace.application.WorkspaceLog;
import jakarta.servlet.http.Cookie;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestConstructor;
import org.springframework.test.context.TestConstructor.AutowireMode;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Tag("acceptance")
@Import(TestcontainersConfiguration.class)
@TestApplicationProperties
@SpringBootTest
@AutoConfigureMockMvc
@TestConstructor(autowireMode = AutowireMode.ALL)
class WorkspaceDepartureLoggingAcceptanceTest {
    private static final String JWT_COOKIE_NAME = "KNOT_ACCESS_TOKEN";
    private static final String CSRF_COOKIE_NAME = "XSRF-TOKEN";
    private static final String REQUEST_ID = "1a2b3c4d5e6f78901a2b3c4d5e6f7890";
    private static final Instant CREATED_AT = Instant.parse("2026-08-31T00:00:00Z");
    private static final Instant JOINED_AT = Instant.parse("2026-08-31T00:01:00Z");

    private final MockMvc mockMvc;
    private final AuthTokenProvider authTokenProvider;
    private final ObjectMapper objectMapper;
    private final JdbcClient jdbcClient;

    WorkspaceDepartureLoggingAcceptanceTest(
            MockMvc mockMvc,
            AuthTokenProvider authTokenProvider,
            ObjectMapper objectMapper,
            JdbcClient jdbcClient
    ) {
        this.mockMvc = mockMvc;
        this.authTokenProvider = authTokenProvider;
        this.objectMapper = objectMapper;
        this.jdbcClient = jdbcClient;
    }

    @BeforeEach
    void clearTables() {
        jdbcClient
                .sql("TRUNCATE TABLE workspace_members, workspaces, oauth_identities, members RESTART IDENTITY CASCADE")
                .update();
    }

    @ParameterizedTest
    @CsvSource({"MEMBER,WORKSPACE_MEMBER_LEFT,workspace.leave,left",
            "TRANSFER,WORKSPACE_OWNERSHIP_TRANSFER_AND_LEFT,workspace.ownership_transfer_and_leave,"
                    + "ownership_transferred_and_left",
            "LAST,WORKSPACE_DELETED,workspace.leave,left_and_workspace_deleted"})
    @DisplayName("녹음 ID만으로 실제 탈퇴 요청과 폐기 원인을 찾는다")
    void depart_success_httpRequestCorrelation(
            String mode,
            String reason,
            String rootEvent,
            String expectedOutcome
    ) throws Exception {
        // given
        long workspaceId = saveWorkspace("비공개팀");
        long ownerId = saveMember("비공개소유자");
        long memberId = saveMember("비공개멤버");
        saveWorkspaceMember(
                workspaceId,
                ownerId,
                "OWNER"
        );
        if (!"LAST".equals(mode)) {
            saveWorkspaceMember(
                    workspaceId,
                    memberId,
                    "MEMBER"
            );
        }
        long departingId = ownerId;
        if ("MEMBER".equals(mode)) {
            departingId = memberId;
        }
        long recordingId = saveRecording(
                workspaceId,
                departingId,
                "RECORDING"
        );
        Cookie accessToken = accessTokenCookie(departingId);
        CsrfCredentials csrf = csrfCredentials();
        MockHttpServletRequestBuilder request = departureRequest(
                mode,
                workspaceId,
                memberId
        );
        request.cookie(
                accessToken,
                csrf.cookie()
        )
                .header(
                        "X-XSRF-TOKEN",
                        csrf.token()
                )
                .header(
                        RequestIdFilter.HEADER,
                        REQUEST_ID
                );
        try (LogCapture logs = new LogCapture(WorkspaceLog.class)) {
            // when
            MvcResult result = mockMvc.perform(request)
                    .andExpect(status().isNoContent())
                    .andReturn();

            // then
            String responseRequestId = result.getResponse()
                    .getHeader(RequestIdFilter.HEADER);
            assertThat(responseRequestId).isEqualTo(REQUEST_ID);
            List<ILoggingEvent> recordingEvents = logs.events()
                    .stream()
                    .filter(
                            event -> Long.valueOf(recordingId)
                                    .equals(
                                            field(
                                                    event,
                                                    "recordingId"
                                            )
                                    )
                    )
                    .toList();
            assertThat(recordingEvents).isNotEmpty()
                    .allSatisfy(event -> {
                        assertThat(
                                field(
                                        event,
                                        "event"
                                )
                        ).isEqualTo("recording.discard");
                        assertThat(
                                field(
                                        event,
                                        "workspaceId"
                                )
                        ).isEqualTo(workspaceId);
                        assertThat(
                                field(
                                        event,
                                        "requestId"
                                )
                        ).isEqualTo(responseRequestId);
                        assertThat(
                                field(
                                        event,
                                        "reason"
                                )
                        ).isEqualTo(reason);
                    });
            assertThat(recordingEvents).anySatisfy(event -> {
                assertThat(
                        field(
                                event,
                                "stage"
                        )
                ).isEqualTo("operation");
                assertThat(
                        field(
                                event,
                                "outcome"
                        )
                ).isEqualTo("started");
            })
                    .anySatisfy(event -> {
                        assertThat(
                                field(
                                        event,
                                        "stage"
                                )
                        ).isEqualTo("operation");
                        assertThat(
                                field(
                                        event,
                                        "outcome"
                                )
                        ).isEqualTo("discarded");
                    });
            assertThat(logs.events()).anySatisfy(event -> {
                assertThat(
                        field(
                                event,
                                "event"
                        )
                ).isEqualTo(rootEvent);
                assertThat(
                        field(
                                event,
                                "requestId"
                        )
                ).isEqualTo(responseRequestId);
            });
            assertThat(logs.events()).anySatisfy(event -> {
                assertThat(
                        field(
                                event,
                                "event"
                        )
                ).isEqualTo(rootEvent);
                assertThat(
                        field(
                                event,
                                "stage"
                        )
                ).isEqualTo("operation");
                assertThat(
                        field(
                                event,
                                "outcome"
                        )
                ).isEqualTo(expectedOutcome);
            });
            assertThat(logs.events()).allSatisfy(event -> {
                JsonNode json = objectMapper.readTree(event.getFormattedMessage());
                assertThat(
                        json.get("requestId")
                                .asText()
                ).isEqualTo(responseRequestId);
                assertThat(event.getFormattedMessage()).doesNotContain(
                        accessToken.getValue(),
                        csrf.token(),
                        "비공개팀",
                        "비공개소유자",
                        "비공개멤버"
                );
            });
        }
    }

    private MockHttpServletRequestBuilder departureRequest(
            String mode,
            long workspaceId,
            long successorMemberId
    ) {
        if ("TRANSFER".equals(mode)) {
            return post("/api/v1/workspaces/" + workspaceId + "/ownership-transfers")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"successorMemberId\":" + successorMemberId + "}");
        }
        return delete("/api/v1/workspaces/" + workspaceId + "/members/me");
    }

    private Object field(
            ILoggingEvent event,
            String name
    ) {
        return event.getKeyValuePairs()
                .stream()
                .filter(pair -> name.equals(pair.key))
                .map(pair -> pair.value)
                .findFirst()
                .orElse(null);
    }

    private CsrfCredentials csrfCredentials() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/auth/csrf"))
                .andExpect(status().isOk())
                .andReturn();
        Cookie cookie = result.getResponse()
                .getCookie(CSRF_COOKIE_NAME);
        assertThat(cookie).isNotNull();
        JsonNode responseBody = objectMapper.readTree(
                result.getResponse()
                        .getContentAsString()
        );
        return new CsrfCredentials(
                cookie,
                responseBody.get("token")
                        .asText()
        );
    }

    private Cookie accessTokenCookie(long memberId) {
        return new Cookie(
                JWT_COOKIE_NAME,
                authTokenProvider.issue(
                        AuthenticatedMember.of(
                                memberId,
                                "hyunsung",
                                null
                        )
                )
        );
    }

    private long saveMember(String nickname) {
        return jdbcClient.sql("""
                INSERT INTO members (nickname, profile_image_url)
                VALUES (:nickname, NULL)
                RETURNING id
                """)
                .param(
                        "nickname",
                        nickname
                )
                .query(Long.class)
                .single();
    }

    private long saveWorkspace(String name) {
        return jdbcClient.sql("""
                INSERT INTO workspaces (name, created_at)
                VALUES (:name, CAST(:createdAt AS TIMESTAMPTZ))
                RETURNING id
                """)
                .param(
                        "name",
                        name
                )
                .param(
                        "createdAt",
                        CREATED_AT.toString()
                )
                .query(Long.class)
                .single();
    }

    private long saveWorkspaceMember(
            long workspaceId,
            long memberId,
            String role
    ) {
        return jdbcClient.sql("""
                INSERT INTO workspace_members (workspace_id, member_id, role, joined_at)
                VALUES (:workspaceId, :memberId, :role, CAST(:joinedAt AS TIMESTAMPTZ))
                RETURNING id
                """)
                .param(
                        "workspaceId",
                        workspaceId
                )
                .param(
                        "memberId",
                        memberId
                )
                .param(
                        "role",
                        role
                )
                .param(
                        "joinedAt",
                        JOINED_AT.toString()
                )
                .query(Long.class)
                .single();
    }

    private long saveRecording(
            long workspaceId,
            long memberId,
            String status
    ) {
        return jdbcClient.sql("""
                INSERT INTO recording_sessions (
                    workspace_id, member_id, request_id, tab_id, control_token_hash, status,
                    started_at, current_interval_started_at, ended_at, last_seen_at
                )
                VALUES (
                    :workspaceId, :memberId, gen_random_uuid(), gen_random_uuid(), repeat('a', 64), :status,
                    CAST(:startedAt AS TIMESTAMPTZ),
                    CASE WHEN :status = 'RECORDING' THEN CAST(:startedAt AS TIMESTAMPTZ) END,
                    CASE WHEN :status = 'ENDED' THEN CAST(:startedAt AS TIMESTAMPTZ) END,
                    CAST(:startedAt AS TIMESTAMPTZ)
                )
                RETURNING id
                """)
                .param(
                        "workspaceId",
                        workspaceId
                )
                .param(
                        "memberId",
                        memberId
                )
                .param(
                        "status",
                        status
                )
                .param(
                        "startedAt",
                        JOINED_AT.toString()
                )
                .query(Long.class)
                .single();
    }

    private record CsrfCredentials(
            Cookie cookie,
            String token
    ) {
    }
}
