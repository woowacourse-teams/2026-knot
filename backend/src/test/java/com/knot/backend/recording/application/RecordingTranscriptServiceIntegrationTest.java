package com.knot.backend.recording.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.knot.backend.global.exception.ErrorCode;
import com.knot.backend.recording.application.dto.result.RecordingTranscriptResult;
import com.knot.backend.recording.domain.RecordingErrorCode;
import com.knot.backend.recording.domain.RecordingException;
import com.knot.backend.testsupport.RecordingHistoryFixture;
import com.knot.backend.testsupport.TestApplicationProperties;
import com.knot.backend.testsupport.TestcontainersConfiguration;
import com.knot.backend.workspace.domain.WorkspaceErrorCode;
import com.knot.backend.workspace.domain.WorkspaceException;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestConstructor;
import org.springframework.test.context.TestConstructor.AutowireMode;

@Tag("integration")
@Import(TestcontainersConfiguration.class)
@TestApplicationProperties
@SpringBootTest
@TestConstructor(autowireMode = AutowireMode.ALL)
class RecordingTranscriptServiceIntegrationTest {
    private static final Instant STARTED_AT = Instant.parse("2026-10-24T09:00:00Z");

    private final RecordingTranscriptService recordingTranscriptService;
    private final RecordingHistoryFixture fixture;
    private long workspaceId;
    private long memberId;
    private long teammateId;

    RecordingTranscriptServiceIntegrationTest(
            RecordingTranscriptService recordingTranscriptService,
            JdbcClient jdbcClient
    ) {
        this.recordingTranscriptService = recordingTranscriptService;
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
    @DisplayName("녹음을 시작하지 않은 멤버도 팀원 녹음의 전사를 읽는다")
    void find_success_teammateRecording() {
        // given
        long recordingId = saved(
                teammateId,
                0
        );
        fixture.batch(
                recordingId,
                "TOPICS_REGISTERED",
                "SUCCEEDED"
        );

        // when
        RecordingTranscriptResult result = find(recordingId);

        // then
        assertThat(result.recordingId()).isEqualTo(recordingId);
        assertThat(result.transcriptText()).isEqualTo("회원 탈퇴 정책을 정했다.");
    }

    @Test
    @DisplayName("다른 Workspace·녹음 중·폐기·음성 저장 실패·없는 녹음은 RECORDING_NOT_FOUND다")
    void find_fail_recordingNotOpenable() {
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
        long recording = withTranscript(
                saved(
                        memberId,
                        1
                )
        );
        fixture.changeStatus(
                recording,
                "RECORDING"
        );
        long discarded = withTranscript(
                saved(
                        teammateId,
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

        // when
        List<ErrorCode> errorCodes = List.of(
                otherWorkspace,
                recording,
                discarded,
                saveFailed,
                Long.MAX_VALUE
        )
                .stream()
                .map(this::errorCode)
                .toList();

        // then
        assertThat(errorCodes).containsOnly(RecordingErrorCode.RECORDING_NOT_FOUND);
    }

    @Test
    @DisplayName("전사 전·전사 실패·NO_CONTENT 녹음은 RECORDING_TRANSCRIPT_NOT_FOUND다")
    void find_fail_transcriptNotFound() {
        // given
        long beforeTranscription = saved(
                memberId,
                0
        );
        long transcriptionFailed = saved(
                memberId,
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

        // when
        List<ErrorCode> errorCodes = List.of(
                beforeTranscription,
                transcriptionFailed,
                noContent
        )
                .stream()
                .map(this::errorCode)
                .toList();

        // then
        assertThat(errorCodes).containsOnly(RecordingErrorCode.RECORDING_TRANSCRIPT_NOT_FOUND);
    }

    @Test
    @DisplayName("현재 Workspace 멤버가 아니면 녹음 전사를 주지 않는다")
    void find_fail_notMember() {
        // given
        long outsiderId = fixture.member("외부인");
        long recordingId = withTranscript(
                saved(
                        memberId,
                        0
                )
        );

        // when & then
        assertThatThrownBy(
                () -> recordingTranscriptService.find(
                        workspaceId,
                        outsiderId,
                        recordingId
                )
        ).isInstanceOf(WorkspaceException.class)
                .extracting("errorCode")
                .isEqualTo(WorkspaceErrorCode.WORKSPACE_ACCESS_DENIED);
    }

    @Test
    @DisplayName("양수가 아닌 녹음 ID는 INVALID_RECORDING_DATA다")
    void find_fail_invalidRecordingId() {
        // when & then
        assertThat(errorCode(0)).isEqualTo(RecordingErrorCode.INVALID_RECORDING_DATA);
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

    private RecordingTranscriptResult find(long recordingId) {
        return recordingTranscriptService.find(
                workspaceId,
                memberId,
                recordingId
        );
    }

    private ErrorCode errorCode(long recordingId) {
        try {
            find(recordingId);
        } catch (RecordingException exception) {
            return exception.getErrorCode();
        }
        throw new AssertionError("녹음 전사 조회가 실패하지 않았다");
    }
}
