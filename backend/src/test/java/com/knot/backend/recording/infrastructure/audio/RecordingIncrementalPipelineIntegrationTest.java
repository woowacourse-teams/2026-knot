package com.knot.backend.recording.infrastructure.audio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

import com.knot.backend.document.DocumentFixtures;
import com.knot.backend.recording.application.RecordingAudioFinalizationTransaction;
import com.knot.backend.recording.application.RecordingAudioFinalizationQuery;
import com.knot.backend.recording.application.RecordingTranscriptionService;
import com.knot.backend.recording.application.RecordingAudioChunkStorage;
import com.knot.backend.recording.application.RecordingAudioFinalStorage;
import com.knot.backend.recording.application.RecordingAudioStorage;
import com.knot.backend.recording.application.TranscriptionClient;
import com.knot.backend.recording.application.RecordingAudioFinalizationWorker;
import com.knot.backend.recording.application.dto.result.AiTranscript;
import com.knot.backend.recording.application.dto.result.AiTranscriptionJob;
import com.knot.backend.recording.application.dto.result.AudioFinalizationClaim;
import com.knot.backend.recording.domain.model.StoredAudioChunk;
import com.knot.backend.recording.domain.repository.RecordingAudioFinalizationRepository;
import com.knot.backend.testsupport.TestApplicationProperties;
import com.knot.backend.testsupport.TestcontainersConfiguration;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@Tag("integration")
@Import(TestcontainersConfiguration.class)
@TestApplicationProperties
@SpringBootTest(properties = {"knot.transcription.enabled=true", "knot.transcription.poll-interval=PT0.01S"})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class RecordingIncrementalPipelineIntegrationTest {
    private static final Instant NOW = DocumentFixtures.CREATED_AT.plusSeconds(121);
    private static final byte[][] PCM = {{1, 2}, {3, 4}, {5, 6}};

    @Autowired
    private RecordingAudioFinalizationTransaction transaction;
    @Autowired
    private RecordingAudioFinalizationRepository jobs;
    @Autowired
    private RecordingAudioFinalizationQuery query;
    @Autowired
    private RecordingTranscriptionService transcriptions;
    @Autowired
    private JdbcClient jdbc;
    @MockitoBean
    private RecordingAudioFinalizationWorker scheduledFinalizer;
    @MockitoBean
    private RecordingAudioChunkStorage chunks;
    @MockitoBean
    private RecordingAudioFinalStorage finalStorage;
    @MockitoBean
    private RecordingAudioStorage audioStorage;
    @MockitoBean
    private TranscriptionClient ai;
    @MockitoBean
    private Clock clock;
    @TempDir
    private Path temporaryDirectory;
    private RecordingAudioFinalizationWorker finalizer;
    private long recordingId;
    private final AtomicReference<byte[]> uploaded = new AtomicReference<>();

    @BeforeEach
    void setUp() throws Exception {
        when(clock.instant()).thenReturn(NOW);
        jdbc.sql("TRUNCATE TABLE members, workspaces RESTART IDENTITY CASCADE")
                .update();
        DocumentFixtures fixtures = new DocumentFixtures(jdbc);
        long workspaceId = fixtures.saveWorkspace();
        long memberId = fixtures.saveMember("조각 녹음자");
        fixtures.join(
                workspaceId,
                memberId
        );
        recordingId = fixtures.saveRecording(
                workspaceId,
                memberId,
                1000
        );
        jdbc.sql("UPDATE recording_sessions SET audio_mode='PCM16_CHUNKS_V1' WHERE id=:id")
                .param(
                        "id",
                        recordingId
                )
                .update();
        jdbc.sql("""
                INSERT INTO recording_audio_streams(recording_id,next_sequence,total_frame_count,
                    seal_kind,sealed_at,accept_until,tail_unknown)
                SELECT id,4,3,'NORMAL',ended_at,ended_at+INTERVAL '120 seconds',FALSE
                FROM recording_sessions WHERE id=:id
                """)
                .param(
                        "id",
                        recordingId
                )
                .update();
        for (int sequence = 1; sequence <= 3; sequence++) {
            reserveChunk(sequence);
        }
        when(finalStorage.findStoredFinal(any())).thenReturn(StoredAudioChunk.missing());
        when(finalStorage.openChunk(any())).thenAnswer(invocation -> {
            com.knot.backend.recording.domain.model.AudioManifestPart part = invocation.getArgument(0);
            return new ByteArrayInputStream(PCM[part.sequence() - 1]);
        });
        when(
                finalStorage.uploadFinal(
                        any(),
                        any(),
                        anyLong(),
                        any()
                )
        ).thenAnswer(invocation -> {
            Path file = invocation.getArgument(1);
            byte[] bytes = Files.readAllBytes(file);
            uploaded.set(bytes);
            return new StoredAudioChunk(
                    true,
                    bytes.length,
                    "audio/wav",
                    checksum(bytes),
                    "final-v1"
            );
        });
        when(
                audioStorage.presignDownload(
                        any(),
                        any(),
                        any(),
                        anyLong()
                )
        ).thenReturn("https://example.invalid/immutable.wav?versionId=final-v1");
        when(
                ai.submit(
                        any(),
                        any(),
                        any()
                )
        ).thenAnswer(
                invocation -> new AiTranscriptionJob(
                        invocation.getArgument(0),
                        AiTranscriptionJob.Status.PROCESSING,
                        null,
                        null
                )
        );
        when(ai.get(any())).thenAnswer(
                invocation -> new AiTranscriptionJob(
                        invocation.getArgument(0),
                        AiTranscriptionJob.Status.SUCCEEDED,
                        null,
                        new AiTranscript(
                                1,
                                "{\"transcription\":[{\"text\":\"녹음 조각 연결 검증\"}]}",
                                List.of(
                                        new AiTranscript.Utterance(
                                                0,
                                                "speaker-A",
                                                0,
                                                1,
                                                "녹음 조각 연결 검증"
                                        )
                                )
                        )
                )
        );
        finalizer = new RecordingAudioFinalizationWorker(
                transaction,
                jobs,
                chunks,
                finalStorage,
                new PcmWavAssembler(
                        finalStorage,
                        temporaryDirectory,
                        () -> 1024L * 1024 * 1024
                ),
                clock
        );
    }

    @AfterEach
    void stopWorkers() {
        finalizer.close();
        transcriptions.close();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void recoveredUnacknowledgedChunksReachTranscriptAndDocumentIntake(boolean missingMiddle) throws Exception {
        // given
        configureObjects(missingMiddle);
        transaction.register(recordingId);
        AudioFinalizationClaim claim = transaction.claim(recordingId)
                .orElseThrow();

        // when
        finalizer.process(claim);

        // then
        awaitTranscript();
        assertThat(uploaded.get()).hasSize(50);
        byte[] expected = {1, 2, 3, 4, 5, 6};
        if (missingMiddle) {
            expected[2] = 0;
            expected[3] = 0;
        }
        assertThat(
                Arrays.copyOfRange(
                        uploaded.get(),
                        44,
                        50
                )
        ).containsExactly(expected);
        assertThat(count("recording_audio_uploads")).isEqualTo(1);
        assertThat(count("recording_transcriptions")).isEqualTo(1);
        assertThat(count("transcripts")).isEqualTo(1);
        assertThat(count("document_generation_batches")).isEqualTo(1);
        assertThat(count("document_generation_jobs")).isEqualTo(1);
        assertThat(
                jobs.findByRecordingId(recordingId)
                        .orElseThrow()
                        .getStatus()
                        .name()
        ).isEqualTo("READY");
        assertThat(
                query.sourceQuality(recordingId)
                        .quality()
        ).isEqualTo(expectedQuality(missingMiddle));
        assertThat(
                query.sourceQuality(recordingId)
                        .missingRanges()
        ).hasSize(expectedMissingCount(missingMiddle));
        jdbc.sql("UPDATE recording_audio_finalizations SET status='DISCARDED' WHERE recording_id=:id")
                .param(
                        "id",
                        recordingId
                )
                .update();
        assertThat(
                query.sourceQuality(recordingId)
                        .quality()
        ).isEqualTo(expectedQuality(missingMiddle));
        try (java.util.stream.Stream<Path> files = Files.list(temporaryDirectory)) {
            assertThat(files.toList()).isEmpty();
        }
    }

    @org.junit.jupiter.api.Test
    void finalPutBeforeRestartIsAdoptedWithoutAnotherUpload() throws Exception {
        // given
        configureObjects(false);
        transaction.register(recordingId);
        AudioFinalizationClaim abandoned = transaction.claim(recordingId)
                .orElseThrow();
        java.util.Map<Integer, StoredAudioChunk> observations = new java.util.HashMap<>();
        for (int index = 1; index <= 3; index++) {
            observations.put(
                    index,
                    new StoredAudioChunk(
                            true,
                            2,
                            "application/octet-stream",
                            checksum(PCM[index - 1]),
                            "version-" + index
                    )
            );
        }
        com.knot.backend.recording.application.dto.result.AudioFinalizationInput frozen = transaction
                .reconcile(
                        abandoned,
                        observations,
                        NOW
                )
                .orElseThrow();
        StoredAudioChunk alreadyUploaded;
        try (com.knot.backend.recording.application.dto.result.FinalizedAudioFile file = new PcmWavAssembler(
                finalStorage,
                temporaryDirectory,
                () -> 1024L * 1024 * 1024
        ).assemble(
                frozen.parts(),
                frozen.totalFrames(),
                () -> {
                }
        )) {
            alreadyUploaded = finalStorage.uploadFinal(
                    frozen.storageKey(),
                    file.path(),
                    file.byteLength(),
                    file.checksumSha256()
            );
        }
        when(finalStorage.findStoredFinal(frozen.storageKey())).thenReturn(alreadyUploaded);
        when(clock.instant()).thenReturn(NOW.plusSeconds(61));
        AudioFinalizationClaim recovered = transaction.claim(recordingId)
                .orElseThrow();

        // when
        finalizer.process(recovered);

        // then
        awaitTranscript();
        org.mockito.Mockito.verify(
                finalStorage,
                org.mockito.Mockito.times(1)
        )
                .uploadFinal(
                        any(),
                        any(),
                        anyLong(),
                        any()
                );
        assertThat(count("recording_audio_uploads")).isEqualTo(1);
        assertThat(count("recording_transcriptions")).isEqualTo(1);
        assertThat(
                jobs.findByRecordingId(recordingId)
                        .orElseThrow()
                        .getAttempt()
        ).isEqualTo(2);
        assertThat(
                org.assertj.core.api.Assertions.catchThrowable(
                        () -> transaction.adopt(
                                abandoned,
                                alreadyUploaded
                        )
                )
        ).isInstanceOf(com.knot.backend.recording.domain.AudioFinalizationLeaseLostException.class);
    }

    private void configureObjects(boolean missingMiddle) throws Exception {
        for (int sequence = 1; sequence <= 3; sequence++) {
            configureObject(
                    sequence,
                    missingMiddle
            );
        }
    }

    private void configureObject(
            int sequence,
            boolean missingMiddle
    ) throws Exception {
        if (missingMiddle && sequence == 2) {
            when(chunks.findStoredObject(key(sequence))).thenReturn(StoredAudioChunk.missing());
            return;
        }
        when(chunks.findStoredObject(key(sequence))).thenReturn(
                new StoredAudioChunk(
                        true,
                        2,
                        "application/octet-stream",
                        checksum(PCM[sequence - 1]),
                        "version-" + sequence
                )
        );
    }

    private void reserveChunk(int sequence) throws Exception {
        jdbc.sql("""
                INSERT INTO recording_audio_chunks(recording_id,sequence,sample_offset,frame_count,
                    byte_length,checksum_sha256,storage_key,status,upload_url_expires_at)
                VALUES (:id,:seq,:offset,1,2,:checksum,:key,'RESERVED',:expiry)
                """)
                .param(
                        "id",
                        recordingId
                )
                .param(
                        "seq",
                        sequence
                )
                .param(
                        "offset",
                        sequence - 1
                )
                .param(
                        "checksum",
                        checksum(PCM[sequence - 1])
                )
                .param(
                        "key",
                        key(sequence)
                )
                .param(
                        "expiry",
                        java.sql.Timestamp.from(NOW.minusSeconds(1))
                )
                .update();
    }

    private String key(int sequence) {
        return "recordings/" + recordingId + "/chunks/" + sequence;
    }

    private String checksum(byte[] bytes) throws Exception {
        return Base64.getEncoder()
                .encodeToString(
                        MessageDigest.getInstance("SHA-256")
                                .digest(bytes)
                );
    }

    private String expectedQuality(boolean missing) {
        if (missing) {
            return "PARTIAL";
        }
        return "COMPLETE";
    }

    private int expectedMissingCount(boolean missing) {
        if (missing) {
            return 1;
        }
        return 0;
    }

    private void awaitTranscript() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            if (jdbc.sql("SELECT count(*) FROM recording_transcriptions WHERE status='SUCCEEDED'")
                    .query(Long.class)
                    .single() == 1) {
                return;
            }
            Thread.sleep(10);
        }
        assertThat(
                jdbc.sql("SELECT status FROM recording_transcriptions")
                        .query(String.class)
                        .single()
        ).isEqualTo("SUCCEEDED");
    }

    private long count(String table) {
        return jdbc.sql("SELECT count(*) FROM " + table)
                .query(Long.class)
                .single();
    }
}
