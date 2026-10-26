package com.knot.backend.recording.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.knot.backend.global.exception.ErrorCode;
import com.knot.backend.recording.application.dto.result.PresignedAudioPlayback;
import com.knot.backend.recording.application.dto.result.RecordingAudioPlaybackResult;
import com.knot.backend.recording.application.dto.result.RecordingAudioPlaybackStatus;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@Tag("integration")
@Import(TestcontainersConfiguration.class)
@TestApplicationProperties
@SpringBootTest
@TestConstructor(autowireMode = AutowireMode.ALL)
class RecordingAudioPlaybackServiceIntegrationTest {
    private static final Instant STARTED_AT = Instant.parse("2026-10-26T09:00:00Z");
    private static final Instant EXPIRES_AT = Instant.parse("2026-10-26T10:00:00Z");
    private static final long RECORDING_DURATION_MILLIS = 61999L;

    private final RecordingAudioPlaybackService recordingAudioPlaybackService;
    private final RecordingHistoryFixture fixture;
    @MockitoBean
    private RecordingAudioStorage recordingAudioStorage;
    private long workspaceId;
    private long memberId;
    private long teammateId;

    RecordingAudioPlaybackServiceIntegrationTest(
            RecordingAudioPlaybackService recordingAudioPlaybackService,
            JdbcClient jdbcClient
    ) {
        this.recordingAudioPlaybackService = recordingAudioPlaybackService;
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
    @DisplayName("녹음을 시작하지 않은 멤버도 저장된 webm 음성의 버전 고정 재생 주소를 받는다")
    void find_success_teammateWebm() {
        // given
        long recordingId = saved(teammateId);
        fixture.pinAudioVersion(
                recordingId,
                "webm-version"
        );
        when(
                recordingAudioStorage.presignPlayback(
                        "recordings/" + recordingId + "/audio.webm",
                        "webm-version",
                        "audio/webm",
                        100L
                )
        ).thenReturn(
                new PresignedAudioPlayback(
                        "https://storage.example/audio.webm?versionId=webm-version",
                        EXPIRES_AT
                )
        );

        // when
        RecordingAudioPlaybackResult result = find(recordingId);

        // then
        assertThat(result).isEqualTo(
                new RecordingAudioPlaybackResult(
                        recordingId,
                        RecordingAudioPlaybackStatus.AVAILABLE,
                        "https://storage.example/audio.webm?versionId=webm-version",
                        EXPIRES_AT,
                        "audio/webm",
                        RECORDING_DURATION_MILLIS
                )
        );
    }

    @Test
    @DisplayName("구간 업로드 녹음은 최종화한 wav 음성의 재생 주소를 받는다")
    void find_success_finalizedWav() {
        // given
        long recordingId = chunked();
        fixture.finalizedWav(recordingId);
        when(
                recordingAudioStorage.presignPlayback(
                        "recordings/" + recordingId + "/final.wav",
                        "wav-version",
                        "audio/wav",
                        1984044L
                )
        ).thenReturn(
                new PresignedAudioPlayback(
                        "https://storage.example/final.wav?versionId=wav-version",
                        EXPIRES_AT
                )
        );

        // when
        RecordingAudioPlaybackResult result = find(recordingId);

        // then
        assertThat(result.audioStatus()).isEqualTo(RecordingAudioPlaybackStatus.AVAILABLE);
        assertThat(result.contentType()).isEqualTo("audio/wav");
        assertThat(result.playbackUrl()).isEqualTo("https://storage.example/final.wav?versionId=wav-version");
    }

    @Test
    @DisplayName("구간 업로드 녹음의 음성 최종화가 대기·진행 중이면 재생 주소 없이 준비 중이다")
    void find_success_preparing() {
        // given
        long waiting = chunked();
        long queued = chunked();
        fixture.finalization(
                queued,
                "QUEUED"
        );
        long processing = chunked();
        fixture.finalization(
                processing,
                "PROCESSING"
        );

        // when
        List<RecordingAudioPlaybackResult> results = List.of(
                waiting,
                queued,
                processing
        )
                .stream()
                .map(this::find)
                .toList();

        // then
        assertThat(results).allSatisfy(result -> {
            assertThat(result.audioStatus()).isEqualTo(RecordingAudioPlaybackStatus.PREPARING);
            assertThat(result.playbackUrl()).isNull();
            assertThat(result.expiresAt()).isNull();
            assertThat(result.contentType()).isNull();
            assertThat(result.recordingDurationMillis()).isEqualTo(RECORDING_DURATION_MILLIS);
        });
        verify(
                recordingAudioStorage,
                never()
        ).presignPlayback(
                anyString(),
                anyString(),
                anyString(),
                anyLong()
        );
    }

    @Test
    @DisplayName("음성이 정리됐거나 정리가 시작됐거나 저장소 버전 기록이 없으면 서명하지 않고 음성 없음이다")
    void find_success_noAudio() {
        // given
        long deleted = saved(memberId);
        fixture.pinAudioVersion(
                deleted,
                "deleted-version"
        );
        fixture.deleteAudio(deleted);
        long revoked = saved(memberId);
        fixture.pinAudioVersion(
                revoked,
                "revoked-version"
        );
        fixture.revokeAudio(revoked);
        long withoutVersion = saved(teammateId);

        // when
        List<RecordingAudioPlaybackResult> results = List.of(
                deleted,
                revoked,
                withoutVersion
        )
                .stream()
                .map(this::find)
                .toList();

        // then
        assertThat(results).allSatisfy(result -> {
            assertThat(result.audioStatus()).isEqualTo(RecordingAudioPlaybackStatus.NO_AUDIO);
            assertThat(result.playbackUrl()).isNull();
            assertThat(result.recordingDurationMillis()).isEqualTo(RECORDING_DURATION_MILLIS);
        });
        verify(
                recordingAudioStorage,
                never()
        ).presignPlayback(
                anyString(),
                anyString(),
                anyString(),
                anyLong()
        );
    }

    @Test
    @DisplayName("처리 결과와 관계없이 저장된 음성이 있으면 들을 수 있다")
    void find_success_regardlessOfProcessing() {
        // given
        long transcriptionFailed = playable(saved(memberId));
        fixture.transcriptionFailed(transcriptionFailed);
        long noContent = playable(saved(memberId));
        fixture.batch(
                noContent,
                "NO_CONTENT",
                "NO_CONTENT"
        );
        long documentFailed = playable(saved(teammateId));
        fixture.failedJob(
                fixture.batch(
                        documentFailed,
                        "TOPICS_REGISTERED",
                        "FAILED"
                )
        );
        when(
                recordingAudioStorage.presignPlayback(
                        anyString(),
                        anyString(),
                        anyString(),
                        anyLong()
                )
        ).thenReturn(
                new PresignedAudioPlayback(
                        "https://storage.example/audio.webm",
                        EXPIRES_AT
                )
        );

        // when
        List<RecordingAudioPlaybackStatus> statuses = List.of(
                transcriptionFailed,
                noContent,
                documentFailed
        )
                .stream()
                .map(this::find)
                .map(RecordingAudioPlaybackResult::audioStatus)
                .toList();

        // then
        assertThat(statuses).containsOnly(RecordingAudioPlaybackStatus.AVAILABLE);
    }

    @Test
    @DisplayName("저장소 확인·서명이 실패하면 AUDIO_STORAGE_UNAVAILABLE을 그대로 알린다")
    void find_fail_storageUnavailable() {
        // given
        long recordingId = playable(saved(memberId));
        when(
                recordingAudioStorage.presignPlayback(
                        anyString(),
                        anyString(),
                        anyString(),
                        anyLong()
                )
        ).thenThrow(new RecordingException(RecordingErrorCode.AUDIO_STORAGE_UNAVAILABLE));

        // when & then
        assertThat(errorCode(recordingId)).isEqualTo(RecordingErrorCode.AUDIO_STORAGE_UNAVAILABLE);
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
        long recording = saved(memberId);
        fixture.changeStatus(
                recording,
                "RECORDING"
        );
        long discarded = saved(teammateId);
        fixture.changeStatus(
                discarded,
                "DISCARDED"
        );
        long saveFailed = chunked();
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
    @DisplayName("현재 Workspace 멤버가 아니면 녹음 음성을 주지 않는다")
    void find_fail_notMember() {
        // given
        long outsiderId = fixture.member("외부인");
        long recordingId = playable(saved(memberId));

        // when & then
        assertThatThrownBy(
                () -> recordingAudioPlaybackService.find(
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

    private long saved(long starterId) {
        return fixture.savedRecording(
                workspaceId,
                starterId,
                STARTED_AT
        );
    }

    private long chunked() {
        return fixture.endedRecording(
                workspaceId,
                memberId,
                STARTED_AT,
                "PCM16_CHUNKS_V1"
        );
    }

    private long playable(long recordingId) {
        fixture.pinAudioVersion(
                recordingId,
                "version-" + recordingId
        );
        return recordingId;
    }

    private RecordingAudioPlaybackResult find(long recordingId) {
        return recordingAudioPlaybackService.find(
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
        throw new AssertionError("녹음 음성 재생 조회가 실패하지 않았다");
    }
}
