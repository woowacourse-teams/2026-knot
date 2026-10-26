package com.knot.backend.document.presentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.knot.backend.auth.domain.AuthTokenProvider;
import com.knot.backend.auth.domain.model.AuthenticatedMember;
import com.knot.backend.document.DocumentFixtures;
import com.knot.backend.recording.application.RecordingAudioStorage;
import com.knot.backend.testsupport.TestApplicationProperties;
import com.knot.backend.testsupport.TestcontainersConfiguration;
import jakarta.servlet.http.Cookie;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@Tag("acceptance")
@ActiveProfiles("dev")
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@TestApplicationProperties
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class DocumentRetentionAcceptanceTest {
    private static final String TRANSCRIPT_PATH = "/api/v1/workspaces/{workspaceId}/documents/{documentId}/transcript";
    private static final String RETRY_PATH = "/api/v1/workspaces/{workspaceId}/document-generation-jobs/{jobId}/retry";
    private static final String JOBS_PATH = "/api/v1/workspaces/{workspaceId}/document-generation-jobs";

    @Autowired
    private MockMvc mvc;
    @Autowired
    private JdbcClient jdbc;
    @Autowired
    private AuthTokenProvider tokens;
    @MockitoBean
    private Clock clock;
    @MockitoBean
    private RecordingAudioStorage storage;
    private DocumentFixtures fixtures;
    private long member;
    private long workspace;
    private long recording;
    private long transcript;

    @BeforeEach
    void setUp() {
        when(clock.instant()).thenReturn(DocumentFixtures.CREATED_AT.plus(Duration.ofDays(30)));
        jdbc.sql("TRUNCATE TABLE members, workspaces RESTART IDENTITY CASCADE")
                .update();
        fixtures = new DocumentFixtures(jdbc);
        member = fixtures.saveMember("녹음자");
        workspace = fixtures.saveWorkspace();
        fixtures.join(
                workspace,
                member
        );
        recording = fixtures.saveRecording(
                workspace,
                member,
                120000
        );
        transcript = fixtures.saveTranscript(recording);
        jdbc.sql("""
                INSERT INTO transcript_segments (transcript_id, position, start_millis, text)
                VALUES (:id, 0, 1200, '발언 원문')
                """)
                .param(
                        "id",
                        transcript
                )
                .update();
    }

    @Test
    @DisplayName("이전 정책으로 오디오가 삭제됐어도 성공 문서의 원문을 조회한다")
    void findTranscript_successAfterAudioDeletion() throws Exception {
        // given
        fixtures.saveJob(
                transcript,
                "FAILED"
        );
        long job = fixtures.saveJob(
                transcript,
                "SUCCEEDED"
        );
        String topic = jdbc.sql("SELECT topic FROM document_generation_jobs WHERE id = :id")
                .param(
                        "id",
                        job
                )
                .query(String.class)
                .single();
        long document = fixtures.saveDocument(
                workspace,
                recording,
                transcript,
                job,
                topic
        );
        long upload = jdbc.sql("""
                INSERT INTO recording_audio_uploads
                    (recording_id, storage_key, content_type, content_length, status, reserved_at, completed_at)
                VALUES (:recording, 'recordings/key', 'audio/webm', 100, 'COMPLETED', :time, :time) RETURNING id
                """)
                .param(
                        "recording",
                        recording
                )
                .param(
                        "time",
                        Timestamp.from(DocumentFixtures.CREATED_AT)
                )
                .query(Long.class)
                .single();

        // when
        jdbc.sql("UPDATE recording_audio_uploads SET deleted_at=:at WHERE id=:id")
                .param(
                        "at",
                        Timestamp.from(clock.instant())
                )
                .param(
                        "id",
                        upload
                )
                .update();

        // then
        assertThat(
                jdbc.sql("SELECT count(*) FROM recording_audio_uploads WHERE deleted_at IS NOT NULL")
                        .query(Integer.class)
                        .single()
        ).isEqualTo(1);
        mvc.perform(
                get(
                        TRANSCRIPT_PATH,
                        workspace,
                        document
                ).cookie(cookie())
        )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.transcriptId").value(transcript))
                .andExpect(jsonPath("$.transcriptText").value("전체 녹음 원문"))
                .andExpect(jsonPath("$.segments[0].text").value("발언 원문"));
        mvc.perform(
                get(
                        TRANSCRIPT_PATH,
                        workspace,
                        document
                )
        )
                .andExpect(status().isUnauthorized());
        long other = fixtures.saveWorkspace();
        mvc.perform(
                get(
                        TRANSCRIPT_PATH,
                        other,
                        document
                ).cookie(cookie())
        )
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("마지막 실패 후 30일이 지난 Job도 원문이 남아 재시도 202이고 표시 기한이 지나 진행 목록에서는 빠진다")
    void retry_successAfterHomeDisplayPeriod() throws Exception {
        // given
        long job = fixtures.saveJob(
                transcript,
                "FAILED"
        );
        Cookie csrf = mvc.perform(get("/api/v1/auth/csrf"))
                .andReturn()
                .getResponse()
                .getCookie("XSRF-TOKEN");
        assertThat(csrf).isNotNull();
        mvc.perform(
                get(
                        JOBS_PATH,
                        workspace
                ).cookie(cookie())
        )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(0));

        // when
        mvc.perform(
                post(
                        RETRY_PATH,
                        workspace,
                        job
                ).cookie(
                        cookie(),
                        csrf
                )
                        .header(
                                "X-XSRF-TOKEN",
                                csrf.getValue()
                        )
                        .contentType(MediaType.APPLICATION_JSON)
        )
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("QUEUED"));

        // then
        assertThat(
                jdbc.sql("SELECT count(*) FROM transcripts WHERE id = :id")
                        .param(
                                "id",
                                transcript
                        )
                        .query(Integer.class)
                        .single()
        ).isEqualTo(1);
        mvc.perform(
                get(
                        JOBS_PATH,
                        workspace
                ).cookie(cookie())
        )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].status").value("QUEUED"));
    }

    private Cookie cookie() {
        return new Cookie(
                "KNOT_ACCESS_TOKEN",
                tokens.issue(
                        AuthenticatedMember.of(
                                member,
                                "녹음자",
                                null
                        )
                )
        );
    }
}
