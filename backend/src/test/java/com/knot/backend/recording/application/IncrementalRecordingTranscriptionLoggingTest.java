package com.knot.backend.recording.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import com.knot.backend.global.config.RecordingAudioChunksProperties;
import com.knot.backend.document.application.DocumentGenerationIntakeService;
import com.knot.backend.recording.domain.model.RecordingSession;
import com.knot.backend.recording.domain.model.RecordingAudioUpload;
import com.knot.backend.recording.domain.model.RecordingTranscription;
import com.knot.backend.recording.domain.model.TranscriptionStatus;
import com.knot.backend.recording.domain.model.Transcript;
import com.knot.backend.recording.domain.repository.RecordingAudioCleanupRepository;
import com.knot.backend.recording.domain.repository.RecordingAudioUploadRepository;
import com.knot.backend.recording.domain.repository.RecordingSessionRepository;
import com.knot.backend.recording.domain.repository.RecordingTranscriptionRepository;
import com.knot.backend.recording.domain.repository.RecordingTranscriptionInputRepository;
import com.knot.backend.recording.domain.repository.RecordingTranscriptionWindowRepository;
import com.knot.backend.recording.domain.repository.TranscriptRepository;
import com.knot.backend.recording.domain.repository.TranscriptSegmentRepository;
import com.knot.backend.workspace.domain.repository.WorkspaceRepository;
import com.knot.backend.workspace.domain.model.Workspace;
import com.knot.backend.recording.application.dto.result.AiTranscript;
import com.knot.backend.recording.domain.RecordingErrorCode;
import com.knot.backend.recording.domain.RecordingException;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mock;
import org.mockito.InjectMocks;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;

@ExtendWith(MockitoExtension.class)
class IncrementalRecordingTranscriptionLoggingTest {
    private static final Instant NOW = Instant.parse("2026-10-17T00:00:00Z");
    private static final UUID ATTEMPT = UUID.fromString("019bbbc0-51ae-7000-8000-000000000001");

    @Mock
    private RecordingAccess access;
    @Mock
    private WorkspaceRepository workspaces;
    @Mock
    private RecordingSessionRepository sessions;
    @Mock
    private RecordingAudioUploadRepository uploads;
    @Mock
    private RecordingTranscriptionRepository transcriptions;
    @Mock
    private RecordingTranscriptionWindowRepository windows;
    @Mock
    private TranscriptRepository transcripts;
    @Mock
    private TranscriptSegmentRepository segments;
    @Mock
    private DocumentGenerationIntakeService documents;
    @Mock
    private Clock clock;
    @Mock
    private RecordingTranscriptionInputRepository inputs;
    @Mock
    private RecordingAudioChunksProperties properties;
    @Mock
    private RecordingAudioCleanupRepository cleanup;
    @Mock
    private RecordingSession session;
    @Mock
    private Workspace workspace;
    @Mock
    private RecordingAudioUpload upload;
    @Mock
    private Transcript transcript;
    @InjectMocks
    private RecordingTranscriptionStateService state;
    private final PersistenceAppender appender = new PersistenceAppender();
    private final Logger logger = (Logger) LoggerFactory.getLogger(RecordingLog.class);

    @BeforeEach
    void captureLogs() {
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void stopCapturing() {
        logger.detachAppender(appender);
        appender.stop();
    }

    @Test
    @DisplayName("구간 전사 저장 완료를 원문 없이 같은 부모 시도로 기록한다")
    void completeIncremental_recordsSuccessfulPersistence() {
        // given
        available(true);
        RecordingTranscription parent = queuedParent();
        availableUpload();
        when(session.getWorkspaceId()).thenReturn(1L);
        when(transcripts.saveAndFlush(any())).thenReturn(transcript);
        when(transcript.getId()).thenReturn(3L);

        // when
        state.completeIncremental(
                2,
                result()
        );

        // then
        assertThat(parent.getStatus()).isEqualTo(TranscriptionStatus.SUCCEEDED);
        assertTerminal(
                "completed",
                null,
                ATTEMPT
        );
        verify(documents).acceptCompletedTranscript(
                1,
                2,
                3
        );
    }

    @Test
    @DisplayName("빈 구간 전사는 NO_CONTENT 실패를 기록하고 원문을 생성하지 않는다")
    void completeIncremental_recordsNoContentWithoutPersistingTranscript() {
        // given
        available(true);
        RecordingTranscription parent = queuedParent();
        availableUpload();
        AiTranscript empty = new AiTranscript(
                1,
                "{\"transcription\":[]}",
                List.of()
        );

        // when
        state.completeIncremental(
                2,
                empty
        );

        // then
        assertThat(parent.getStatus()).isEqualTo(TranscriptionStatus.FAILED);
        assertThat(parent.getFailureCode()).isEqualTo("NO_CONTENT");
        assertTerminal(
                "failed",
                "NO_CONTENT",
                ATTEMPT
        );
        verify(
                transcripts,
                never()
        ).saveAndFlush(any());
    }

    @ParameterizedTest
    @CsvSource({"INCREMENTAL_MERGE_FAILED,INCREMENTAL_MERGE_FAILED",
            "INCREMENTAL_WINDOW_FAILED,INCREMENTAL_WINDOW_FAILED", "PRIVATE_EMBEDDING,UNKNOWN_FAILURE"})
    @DisplayName("구간 실패 원인은 안전한 고정 코드만 로그에 기록한다")
    void failIncremental_recordsOnlyAllowlistedFailureCode(
            String code,
            String loggedCode
    ) {
        // given
        available(true);
        RecordingTranscription parent = queuedParent();

        // when
        state.failIncremental(
                2,
                code
        );

        // then
        assertThat(parent.getStatus()).isEqualTo(TranscriptionStatus.FAILED);
        assertThat(parent.getFailureCode()).isEqualTo(code);
        assertTerminal(
                "failed",
                loggedCode,
                ATTEMPT
        );
    }

    @ParameterizedTest
    @CsvSource({"true", "false"})
    @DisplayName("전사할 수 없게 된 녹음의 구간 전사 완료와 실패 제출을 거절로 기록한다")
    void incrementalPersistence_recordsRejectionWithoutChangingState(boolean failedResult) {
        // given
        available(false);

        // when
        assertThatThrownBy(() -> {
            if (failedResult) {
                state.failIncremental(
                        2,
                        "INCREMENTAL_MERGE_FAILED"
                );
            } else {
                state.completeIncremental(
                        2,
                        result()
                );
            }
        }).isInstanceOf(RecordingException.class);

        // then
        assertTerminal(
                "rejected",
                RecordingErrorCode.RECORDING_NOT_FOUND.getCode(),
                null
        );
        verify(
                transcriptions,
                never()
        ).findByRecordingSessionIdForUpdate(2);
        verify(
                transcripts,
                never()
        ).saveAndFlush(any());
    }

    private void available(boolean transcribable) {
        when(sessions.findWorkspaceIdById(2)).thenReturn(Optional.of(1L));
        when(workspaces.findByIdForUpdate(1L)).thenReturn(Optional.of(workspace));
        when(sessions.findByIdForUpdate(2)).thenReturn(Optional.of(session));
        when(session.getIncrementalPipelineVersion()).thenReturn("pcm16-window-v1");
        when(session.isTranscribableIn(1)).thenReturn(transcribable);
    }

    private RecordingTranscription queuedParent() {
        RecordingTranscription parent = RecordingTranscription.queued(
                2,
                ATTEMPT,
                NOW,
                "private-storage-key",
                "private-version",
                "audio/wav",
                100,
                null
        );
        when(transcriptions.findByRecordingSessionIdForUpdate(2)).thenReturn(Optional.of(parent));
        when(clock.instant()).thenReturn(NOW);
        return parent;
    }

    private void availableUpload() {
        when(uploads.findByRecordingId(2)).thenReturn(Optional.of(upload));
        when(upload.isAudioAvailable()).thenReturn(true);
    }

    private AiTranscript result() {
        return new AiTranscript(
                1,
                "{\"transcription\":[{\"text\":\"PRIVATE_TRANSCRIPT\"}]," + "\"embedding\":\"PRIVATE_EMBEDDING\"}",
                List.of(
                        new AiTranscript.Utterance(
                                0,
                                "화자 1",
                                0,
                                1,
                                "PRIVATE_TRANSCRIPT"
                        )
                )
        );
    }

    private void assertTerminal(
            String outcome,
            String code,
            UUID attempt
    ) {
        assertThat(appender.events).hasSize(2);
        ILoggingEvent terminal = appender.events.getLast();
        Map<String, Object> fields = new LinkedHashMap<>();
        terminal.getKeyValuePairs()
                .forEach(
                        pair -> fields.put(
                                pair.key,
                                pair.value
                        )
                );
        assertThat(fields).containsEntry(
                "event",
                "recording.transcription.persist"
        )
                .containsEntry(
                        "outcome",
                        outcome
                )
                .containsEntry(
                        "workspaceId",
                        1L
                )
                .containsEntry(
                        "recordingId",
                        2L
                )
                .containsEntry(
                        "attemptId",
                        attempt == null ? null : attempt.toString()
                );
        if (code == null) {
            assertThat(fields).doesNotContainKey("errorCode");
        } else {
            assertThat(fields).containsEntry(
                    "errorCode",
                    code
            );
        }
        assertThat(appender.events).allSatisfy(
                event -> assertThat(event.getFormattedMessage()).doesNotContain(
                        "PRIVATE_TRANSCRIPT",
                        "PRIVATE_EMBEDDING",
                        "private-storage-key",
                        "private-version"
                )
        );
    }

    private static final class PersistenceAppender extends AppenderBase<ILoggingEvent> {
        private final List<ILoggingEvent> events = new CopyOnWriteArrayList<>();

        @Override
        protected void append(ILoggingEvent event) {
            event.prepareForDeferredProcessing();
            events.add(event);
        }
    }
}
