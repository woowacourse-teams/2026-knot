package com.knot.backend.recording.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.knot.backend.recording.domain.model.RecordingAudioMode;
import com.knot.backend.recording.domain.model.RecordingSession;
import com.knot.backend.recording.domain.model.RecordingTranscriptionWindow;
import com.knot.backend.recording.domain.model.RecordingTranscriptionInput;
import com.knot.backend.recording.domain.model.StoredAudioChunk;
import com.knot.backend.recording.domain.repository.IncrementalTranscriptionQueue;
import com.knot.backend.recording.domain.repository.RecordingTranscriptionWindowRepository;
import com.knot.backend.testsupport.TestcontainersConfiguration;
import jakarta.persistence.EntityManager;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

@Tag("integration")
@Import({TestcontainersConfiguration.class, RecordingTranscriptionWindowRepositoryAdapter.class,
        IncrementalTranscriptionQueueAdapter.class})
@DataJpaTest
class IncrementalTranscriptionQueueAdapterIntegrationTest {
    private static final Instant NOW = Instant.parse("2026-10-18T00:00:00Z");
    private static final String PIPELINE = "pcm-window-v1-30-5";

    @Autowired
    private IncrementalTranscriptionQueue queue;
    @Autowired
    private RecordingTranscriptionWindowRepository windows;
    @Autowired
    private EntityManager entityManager;
    @Autowired
    private JdbcClient jdbc;

    @Test
    void findCandidates_alternatesTwoRecordingsWithBacklogs() {
        // given
        long first = recording();
        long second = recording();
        for (int index = 0; index < 3; index++) {
            window(
                    first,
                    index
            );
            window(
                    second,
                    index
            );
        }
        List<Long> served = new ArrayList<>();

        // when
        for (int index = 0; index < 6; index++) {
            served.add(completeNext(NOW.plusSeconds(index + 1)));
        }

        // then
        assertThat(served).containsExactly(
                first,
                second,
                first,
                second,
                first,
                second
        );
    }

    @Test
    void findCandidates_processRecoveryPrecedesUnservedEndedRecording() {
        // given
        long recovering = recording();
        long ended = recording();
        RecordingTranscriptionWindow processing = window(
                recovering,
                0
        );
        window(
                ended,
                0
        );
        processing.claim(
                UUID.randomUUID(),
                NOW,
                Duration.ofSeconds(5),
                Duration.ofMinutes(5)
        );
        windows.save(processing);
        end(ended);

        // when
        List<IncrementalTranscriptionQueue.WindowTask> candidates = queue.findCandidates(
                NOW.plusSeconds(6),
                2
        );

        // then
        assertThat(
                candidates.getFirst()
                        .recordingId()
        ).isEqualTo(recovering);
        assertThat(
                candidates.getFirst()
                        .windowId()
        ).isEqualTo(processing.getId());
    }

    @Test
    void findCandidates_endedRecordingPrecedesUnservedActiveRecording() {
        // given
        long active = recording();
        long ended = recording();
        window(
                active,
                0
        );
        window(
                ended,
                0
        );
        end(ended);

        // when
        List<IncrementalTranscriptionQueue.WindowTask> candidates = queue.findCandidates(
                NOW,
                2
        );

        // then
        assertThat(
                candidates.getFirst()
                        .recordingId()
        ).isEqualTo(ended);
    }

    @Test
    void findCandidates_servedEndedRecordingKeepsPriorityOverActiveBacklog() {
        // given
        long active = recording();
        long ended = recording();
        window(
                active,
                0
        );
        window(
                ended,
                0
        );
        window(
                ended,
                1
        );
        end(ended);
        assertThat(completeNext(NOW.plusSeconds(1))).isEqualTo(ended);

        // when
        List<IncrementalTranscriptionQueue.WindowTask> candidates = queue.findCandidates(
                NOW.plusSeconds(2),
                2
        );

        // then
        assertThat(
                candidates.getFirst()
                        .recordingId()
        ).isEqualTo(ended);
    }

    @Test
    void findCandidates_endedRecordingsFinishOneByOneInEndOrder() {
        // given
        long active = recording();
        long endedSecond = recording();
        long endedFirst = recording();
        long endedLast = recording();
        for (int index = 0; index < 2; index++) {
            window(
                    active,
                    index
            );
            window(
                    endedSecond,
                    index
            );
            window(
                    endedFirst,
                    index
            );
            window(
                    endedLast,
                    index
            );
        }
        end(
                endedSecond,
                NOW.plusSeconds(10)
        );
        end(
                endedFirst,
                NOW.plusSeconds(5)
        );
        end(
                endedLast,
                NOW.plusSeconds(20)
        );
        List<Long> served = new ArrayList<>();

        // when
        for (int index = 0; index < 8; index++) {
            served.add(completeNext(NOW.plusSeconds(index + 30)));
        }

        // then
        assertThat(served).containsExactly(
                endedFirst,
                endedFirst,
                endedSecond,
                endedSecond,
                endedLast,
                endedLast,
                active,
                active
        );
    }

    @ParameterizedTest
    @ValueSource(strings = {"FAILED", "SUCCEEDED"})
    void findCandidates_excludesTerminalParentQueuedWindowsButPreservesRecovery(String parentStatus) {
        // given
        long failed = recording();
        long ready = recording();
        RecordingTranscriptionWindow processing = window(
                failed,
                0
        );
        window(
                failed,
                1
        );
        window(
                ready,
                0
        );
        processing.claim(
                UUID.randomUUID(),
                NOW,
                Duration.ofSeconds(5),
                Duration.ofMinutes(5)
        );
        windows.save(processing);
        jdbc.sql("""
                INSERT INTO recording_transcriptions(recording_session_id,attempt_id,status,
                    failure_code,queued_at,next_run_at)
                VALUES (:id,gen_random_uuid(),:status,
                    CASE WHEN :status='FAILED' THEN 'WINDOW_FAILED' ELSE NULL END,:now,:now)
                """)
                .param(
                        "id",
                        failed
                )
                .param(
                        "status",
                        parentStatus
                )
                .param(
                        "now",
                        java.sql.Timestamp.from(NOW)
                )
                .update();

        // when
        List<IncrementalTranscriptionQueue.WindowTask> candidates = queue.findCandidates(
                NOW.plusSeconds(6),
                10
        );

        // then
        assertThat(candidates).hasSize(2);
        assertThat(
                candidates.getFirst()
                        .windowId()
        ).isEqualTo(processing.getId());
        assertThat(
                candidates.get(1)
                        .recordingId()
        ).isEqualTo(ready);
    }

    @Test
    void findCandidates_servesPreparedAfterTwoPcmWindows() {
        // given
        long firstPcm = recording();
        long secondPcm = recording();
        long prepared = preparedRecording();
        for (int index = 0; index < 3; index++) {
            window(
                    firstPcm,
                    index
            );
            window(
                    secondPcm,
                    index
            );
            window(
                    prepared,
                    index,
                    RecordingTranscriptionInput.PIPELINE
            );
        }
        List<Long> served = new ArrayList<>();

        // when
        for (int index = 0; index < 9; index++) {
            served.add(completeNext(NOW.plusSeconds(index + 1)));
        }

        // then
        assertThat(served).containsExactly(
                firstPcm,
                secondPcm,
                prepared,
                firstPcm,
                secondPcm,
                prepared,
                firstPcm,
                secondPcm,
                prepared
        );
        assertThat(
                jdbc.sql("SELECT COUNT(*) FROM recording_audio_streams WHERE recording_id=:id")
                        .param(
                                "id",
                                prepared
                        )
                        .query(Long.class)
                        .single()
        ).isZero();
    }

    @Test
    void findCandidates_preparedOnlyBacklogsStillAlternateRecordings() {
        // given
        long first = preparedRecording();
        long second = preparedRecording();
        for (int index = 0; index < 2; index++) {
            window(
                    first,
                    index,
                    RecordingTranscriptionInput.PIPELINE
            );
            window(
                    second,
                    index,
                    RecordingTranscriptionInput.PIPELINE
            );
        }
        List<Long> served = new ArrayList<>();

        // when
        for (int index = 0; index < 4; index++) {
            served.add(completeNext(NOW.plusSeconds(index + 1)));
        }

        // then
        assertThat(served).containsExactly(
                first,
                second,
                first,
                second
        );
    }

    @Test
    void findCandidates_processingRecoveryPrecedesPreparedTurn() {
        // given
        long pcm = recording();
        long prepared = preparedRecording();
        window(
                pcm,
                0
        );
        window(
                pcm,
                1
        );
        RecordingTranscriptionWindow recovering = window(
                pcm,
                2
        );
        window(
                prepared,
                0,
                RecordingTranscriptionInput.PIPELINE
        );
        assertThat(completeNext(NOW.plusSeconds(1))).isEqualTo(pcm);
        assertThat(completeNext(NOW.plusSeconds(2))).isEqualTo(pcm);
        recovering.claim(
                UUID.randomUUID(),
                NOW.plusSeconds(3),
                Duration.ofSeconds(5),
                Duration.ofMinutes(5)
        );
        windows.save(recovering);

        // when
        List<IncrementalTranscriptionQueue.WindowTask> candidates = queue.findCandidates(
                NOW.plusSeconds(9),
                2
        );

        // then
        assertThat(candidates).extracting(IncrementalTranscriptionQueue.WindowTask::recordingId)
                .containsExactly(
                        pcm,
                        prepared
                );
    }

    @Test
    void findCandidates_onePcmClaimDoesNotYieldPreparedTurn() {
        // given
        long pcm = recording();
        long prepared = preparedRecording();
        window(
                pcm,
                0
        );
        window(
                pcm,
                1
        );
        window(
                prepared,
                0,
                RecordingTranscriptionInput.PIPELINE
        );
        assertThat(completeNext(NOW.plusSeconds(1))).isEqualTo(pcm);

        // when
        List<IncrementalTranscriptionQueue.WindowTask> candidates = queue.findCandidates(
                NOW.plusSeconds(2),
                2
        );

        // then
        assertThat(candidates).extracting(IncrementalTranscriptionQueue.WindowTask::recordingId)
                .containsExactly(
                        pcm,
                        prepared
                );
    }

    @Test
    void findCandidates_tiedClaimTimestampsUseWindowIdForRecentHistory() {
        // given
        long pcm = recording();
        long prepared = preparedRecording();
        window(
                pcm,
                0
        );
        window(
                pcm,
                1
        );
        window(
                pcm,
                2
        );
        window(
                prepared,
                0,
                RecordingTranscriptionInput.PIPELINE
        );
        assertThat(completeNext(NOW.plusSeconds(1))).isEqualTo(pcm);
        assertThat(completeNext(NOW.plusSeconds(1))).isEqualTo(pcm);

        // when
        List<IncrementalTranscriptionQueue.WindowTask> candidates = queue.findCandidates(
                NOW.plusSeconds(2),
                2
        );

        // then
        assertThat(candidates).extracting(IncrementalTranscriptionQueue.WindowTask::recordingId)
                .containsExactly(
                        prepared,
                        pcm
                );
    }

    @Test
    void findCandidates_reclaimingOneWindowDoesNotCountAsTwoPcmWindows() {
        // given
        long pcm = recording();
        long prepared = preparedRecording();
        RecordingTranscriptionWindow retried = window(
                pcm,
                0
        );
        window(
                pcm,
                1
        );
        window(
                prepared,
                0,
                RecordingTranscriptionInput.PIPELINE
        );
        retried.claim(
                UUID.randomUUID(),
                NOW,
                Duration.ofSeconds(1),
                Duration.ofMinutes(5)
        );
        windows.save(retried);
        UUID recoveredOwner = UUID.randomUUID();
        retried.claim(
                recoveredOwner,
                NOW.plusSeconds(2),
                Duration.ofSeconds(30),
                Duration.ofMinutes(5)
        );
        retried.succeed(
                retried.getAttemptId(),
                recoveredOwner,
                retried.getClaimEpoch(),
                NOW.plusSeconds(2),
                1,
                "{}"
        );
        windows.save(retried);

        // when
        List<IncrementalTranscriptionQueue.WindowTask> candidates = queue.findCandidates(
                NOW.plusSeconds(3),
                2
        );

        // then
        assertThat(candidates).extracting(IncrementalTranscriptionQueue.WindowTask::recordingId)
                .containsExactly(
                        pcm,
                        prepared
                );
    }

    private long completeNext(Instant now) {
        IncrementalTranscriptionQueue.WindowTask next = queue.findCandidates(
                now,
                1
        )
                .getFirst();
        RecordingTranscriptionWindow window = windows.findByIdForUpdate(next.windowId())
                .orElseThrow();
        UUID owner = UUID.randomUUID();
        assertThat(
                window.claim(
                        owner,
                        now,
                        Duration.ofSeconds(30),
                        Duration.ofMinutes(5)
                )
        ).isTrue();
        assertThat(
                window.succeed(
                        window.getAttemptId(),
                        owner,
                        window.getClaimEpoch(),
                        now,
                        1,
                        "{}"
                )
        ).isTrue();
        windows.save(window);
        entityManager.clear();
        return next.recordingId();
    }

    private RecordingTranscriptionWindow window(
            long recordingId,
            int index
    ) {
        return window(
                recordingId,
                index,
                PIPELINE
        );
    }

    private RecordingTranscriptionWindow window(
            long recordingId,
            int index,
            String pipeline
    ) {
        return windows.save(
                RecordingTranscriptionWindow.queued(
                        recordingId,
                        pipeline,
                        index,
                        index * 480000L,
                        (index + 1L) * 480000,
                        index * 480000L,
                        (index + 1L) * 480000,
                        "{}",
                        UUID.randomUUID(),
                        NOW
                )
        );
    }

    private long preparedRecording() {
        long id = recording(RecordingAudioMode.FINAL_WEBM_V1);
        end(id);
        RecordingTranscriptionInput input = RecordingTranscriptionInput.queued(
                id,
                "recordings/" + id + "/audio.webm",
                "original-v1",
                "audio/webm",
                100,
                NOW
        );
        input.claim(
                UUID.randomUUID(),
                NOW,
                Duration.ofSeconds(30)
        );
        String key = "recordings/" + id + "/prepared/" + input.getAttemptId() + "/" + "0".repeat(64) + ".wav";
        input.ready(
                key,
                new StoredAudioChunk(
                        true,
                        44 + 1_920_000L * 2,
                        "audio/wav",
                        "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
                        "prepared-v1"
                ),
                1_920_000
        );
        entityManager.persist(input);
        entityManager.flush();
        return id;
    }

    private long recording() {
        return recording(RecordingAudioMode.PCM16_CHUNKS_V1);
    }

    private long recording(RecordingAudioMode mode) {
        long member = jdbc.sql("INSERT INTO members(nickname) VALUES ('순환 검증') RETURNING id")
                .query(Long.class)
                .single();
        long workspace = jdbc.sql("INSERT INTO workspaces(name,created_at) VALUES ('순환 검증',now()) RETURNING id")
                .query(Long.class)
                .single();
        RecordingSession session = RecordingSession.start(
                workspace,
                member,
                UUID.randomUUID(),
                UUID.randomUUID(),
                "a".repeat(64),
                NOW,
                mode
        );
        if (mode == RecordingAudioMode.PCM16_CHUNKS_V1) {
            session.enableIncrementalTranscription(PIPELINE);
        }
        entityManager.persist(session);
        entityManager.flush();
        return session.getId();
    }

    private void end(long recordingId) {
        end(
                recordingId,
                NOW
        );
    }

    private void end(
            long recordingId,
            Instant endedAt
    ) {
        jdbc.sql("""
                UPDATE recording_sessions SET status='ENDED',ended_at=:now,end_reason='USER_ENDED',
                    current_interval_started_at=NULL WHERE id=:id
                """)
                .param(
                        "now",
                        java.sql.Timestamp.from(endedAt)
                )
                .param(
                        "id",
                        recordingId
                )
                .update();
    }
}
