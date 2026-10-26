package com.knot.backend.recording.presentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItems;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.knot.backend.auth.domain.AuthTokenProvider;
import com.knot.backend.auth.domain.model.AuthenticatedMember;
import com.knot.backend.recording.application.RecordingAudioStorage;
import com.knot.backend.recording.application.dto.result.PresignedAudioPlayback;
import com.knot.backend.recording.domain.RecordingErrorCode;
import com.knot.backend.recording.domain.RecordingException;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@Tag("acceptance")
@ActiveProfiles("dev")
@Import(TestcontainersConfiguration.class)
@TestApplicationProperties
@SpringBootTest
@AutoConfigureMockMvc
@TestConstructor(autowireMode = AutowireMode.ALL)
class RecordingAudioPlaybackAcceptanceTest {
    private static final String JWT_COOKIE_NAME = "KNOT_ACCESS_TOKEN";
    private static final String PATH = "/api/v1/workspaces/{workspaceId}/recordings/{recordingId}/audio-playback";
    private static final Instant STARTED_AT = Instant.parse("2026-10-26T01:30:00Z");

    private final MockMvc mockMvc;
    private final AuthTokenProvider authTokenProvider;
    private final JdbcClient jdbcClient;
    private final RecordingHistoryFixture fixture;
    @MockitoBean
    private RecordingAudioStorage recordingAudioStorage;
    private long workspaceId;
    private long memberId;
    private long teammateId;

    RecordingAudioPlaybackAcceptanceTest(
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
    @DisplayName("녹음을 시작하지 않은 멤버도 재생 주소를 no-store로 받고 녹음·업로드 상태는 바뀌지 않는다")
    void findPlayback_success_teammateRecording() throws Exception {
        // given
        long recordingId = playable(teammateId);
        when(
                recordingAudioStorage.presignPlayback(
                        anyString(),
                        anyString(),
                        anyString(),
                        anyLong()
                )
        ).thenReturn(
                new PresignedAudioPlayback(
                        "https://storage.example/audio.webm?versionId=v1",
                        Instant.parse("2026-10-26T10:00:00Z")
                )
        );
        List<Map<String, Object>> before = storedState(recordingId);

        // when
        ResultActions result = find(
                workspaceId,
                String.valueOf(recordingId),
                memberId
        );

        // then
        result.andExpect(status().isOk())
                .andExpect(
                        header().string(
                                "Cache-Control",
                                "no-store"
                        )
                )
                .andExpect(jsonPath("$.recordingId").value(recordingId))
                .andExpect(jsonPath("$.audioStatus").value("AVAILABLE"))
                .andExpect(jsonPath("$.playbackUrl").value("https://storage.example/audio.webm?versionId=v1"))
                .andExpect(jsonPath("$.expiresAt").value("2026-10-26T10:00:00Z"))
                .andExpect(jsonPath("$.contentType").value("audio/webm"))
                .andExpect(jsonPath("$.recordingDurationMillis").value(61999));
        assertThat(storedState(recordingId)).isEqualTo(before);
    }

    @Test
    @DisplayName("저장소 버전 기록이 없는 옛 업로드는 500이 아니라 200 NO_AUDIO다")
    void findPlayback_success_noAudioWithoutVersion() throws Exception {
        // given
        long recordingId = fixture.savedRecording(
                workspaceId,
                memberId,
                STARTED_AT
        );

        // when
        ResultActions result = find(
                workspaceId,
                String.valueOf(recordingId),
                memberId
        );

        // then
        result.andExpect(status().isOk())
                .andExpect(jsonPath("$.audioStatus").value("NO_AUDIO"))
                .andExpect(jsonPath("$.playbackUrl").isEmpty())
                .andExpect(jsonPath("$.expiresAt").isEmpty())
                .andExpect(jsonPath("$.contentType").isEmpty());
    }

    @Test
    @DisplayName("저장소 확인이나 서명이 실패하면 500 AUDIO_STORAGE_UNAVAILABLE이다")
    void findPlayback_fail_storageUnavailable() throws Exception {
        // given
        long recordingId = playable(memberId);
        when(
                recordingAudioStorage.presignPlayback(
                        anyString(),
                        anyString(),
                        anyString(),
                        anyLong()
                )
        ).thenThrow(new RecordingException(RecordingErrorCode.AUDIO_STORAGE_UNAVAILABLE));

        // when
        ResultActions result = find(
                workspaceId,
                String.valueOf(recordingId),
                memberId
        );

        // then
        result.andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("AUDIO_STORAGE_UNAVAILABLE"));
    }

    @Test
    @DisplayName("폐기한 녹음은 404 RECORDING_NOT_FOUND다")
    void findPlayback_fail_discarded() throws Exception {
        // given
        long recordingId = playable(memberId);
        fixture.changeStatus(
                recordingId,
                "DISCARDED"
        );

        // when
        ResultActions result = find(
                workspaceId,
                String.valueOf(recordingId),
                memberId
        );

        // then
        result.andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RECORDING_NOT_FOUND"));
    }

    @Test
    @DisplayName("현재 Workspace 멤버가 아니면 403 WORKSPACE_ACCESS_DENIED다")
    void findPlayback_fail_notMember() throws Exception {
        // given
        long outsiderId = fixture.member("외부인");
        long recordingId = playable(memberId);

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
    void findPlayback_fail_invalidPath(
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
    void findPlayback_fail_unauthenticated() throws Exception {
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
    @DisplayName("OpenAPI JSON에 녹음 음성 재생 조회 계약을 공개한다")
    void findPlayback_success_openApi() throws Exception {
        // when
        ResultActions result = mockMvc.perform(get("/v3/api-docs"));

        // then
        result.andExpect(status().isOk())
                .andExpect(
                        jsonPath(
                                "$.paths['/api/v1/workspaces/{workspaceId}/recordings/{recordingId}/audio-playback'].get.responses['200']"
                        ).exists()
                )
                .andExpect(
                        jsonPath(
                                "$.paths['/api/v1/workspaces/{workspaceId}/recordings/{recordingId}/audio-playback'].get.responses['500'].description"
                        ).value(containsString("AUDIO_STORAGE_UNAVAILABLE"))
                )
                .andExpect(
                        jsonPath("$.components.schemas.RecordingAudioPlaybackResponse.required").value(
                                hasItems(
                                        "recordingId",
                                        "audioStatus",
                                        "playbackUrl",
                                        "expiresAt",
                                        "contentType",
                                        "recordingDurationMillis"
                                )
                        )
                );
    }

    private long playable(long starterId) {
        long recordingId = fixture.savedRecording(
                workspaceId,
                starterId,
                STARTED_AT
        );
        fixture.pinAudioVersion(
                recordingId,
                "version-" + recordingId
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

    private List<Map<String, Object>> storedState(long recordingId) {
        return jdbcClient.sql("""
                SELECT s.status, s.ended_at, s.accumulated_recording_millis, u.status AS upload_status, u.revoked_at,
                    u.deleted_at, u.storage_version_id
                FROM recording_sessions s JOIN recording_audio_uploads u ON u.recording_id = s.id
                WHERE s.id = :recordingId
                """)
                .param(
                        "recordingId",
                        recordingId
                )
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
