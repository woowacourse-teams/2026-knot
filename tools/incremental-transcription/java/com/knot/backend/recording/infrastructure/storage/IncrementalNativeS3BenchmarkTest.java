package com.knot.backend.recording.infrastructure.storage;

import com.knot.backend.document.application.DocumentGenerationIntakeService;
import com.knot.backend.recording.application.RecordingService;
import com.knot.backend.recording.application.RecordingAudioChunkService;
import com.knot.backend.recording.application.RecordingAudioUploadUrlService;
import com.knot.backend.recording.application.RecordingAudioUploadCompletionService;
import com.knot.backend.recording.application.IncrementalTranscriptionWorker;
import com.knot.backend.recording.application.RecordingInputPreparationWorker;
import com.knot.backend.recording.application.RecordingAudioFinalizationWorker;
import com.knot.backend.recording.application.RecordingTranscriptionService;
import com.knot.backend.recording.application.RecordingAudioChunkStorage;
import com.knot.backend.recording.application.RecordingAudioFinalStorage;
import com.knot.backend.recording.application.RecordingAudioStorage;
import com.knot.backend.recording.application.dto.command.RecordingControlCommand;
import com.knot.backend.recording.application.dto.command.RecordingStartCommand;
import com.knot.backend.recording.application.dto.command.RecordingAudioSealCommand;
import com.knot.backend.recording.application.dto.command.RecordingAudioUploadUrlCommand;
import com.knot.backend.recording.application.dto.command.RecordingAudioChunkCommand;
import com.knot.backend.recording.application.dto.result.RecordingAudioUploadUrlResult;
import com.knot.backend.recording.application.dto.result.RecordingAudioChunkReservationResult;
import com.knot.backend.recording.domain.model.RecordingAudioMode;
import com.knot.backend.recording.domain.model.AudioChunkDescriptor;
import com.knot.backend.testsupport.TestApplicationProperties;
import com.knot.backend.testsupport.TestcontainersConfiguration;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.ListObjectVersionsResponse;
import software.amazon.awssdk.services.s3.model.ObjectIdentifier;
import software.amazon.awssdk.services.s3.model.BucketVersioningStatus;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;

/**
 * Opt-in service benchmark. Runs only with an isolated UUID S3 prefix and
 * Testcontainers database.
 */
@org.junit.jupiter.api.Tag("integration")
@EnabledIfEnvironmentVariable(named = "KNOT_E2E_RUN", matches = "true")
@Import({TestcontainersConfiguration.class, IncrementalNativeS3BenchmarkTest.Storage.class})
@TestApplicationProperties
@SpringBootTest(properties = {"recording.audio-chunks.enabled=true", "recording.audio-chunks.incremental-enabled=true",
        "knot.transcription.enabled=true", "knot.transcription.poll-interval=PT0.1S",
        "recording.audio-finalization.enabled=true"})
class IncrementalNativeS3BenchmarkTest {
    private static final String PREFIX = experimentPrefix();
    private static final String BUCKET = System.getenv("KNOT_E2E_BUCKET");
    private static final Region REGION = Region.of(
            System.getenv()
                    .getOrDefault(
                            "KNOT_E2E_REGION",
                            "ap-northeast-2"
                    )
    );

    @Autowired
    private RecordingService recordings;
    @Autowired
    private RecordingAudioChunkService chunks;
    @Autowired
    private RecordingAudioUploadUrlService uploadUrls;
    @Autowired
    private RecordingAudioUploadCompletionService uploadCompletion;
    @Autowired
    private JdbcClient jdbc;
    @Autowired
    private S3Client e2eS3;
    @Autowired
    private ObjectMapper mapper;
    @Autowired
    private IncrementalTranscriptionWorker incrementalWorker;
    @Autowired
    private RecordingInputPreparationWorker preparationWorker;
    @Autowired
    private RecordingAudioFinalizationWorker finalizationWorker;
    @Autowired
    private RecordingTranscriptionService legacyWorker;
    private final java.util.concurrent.ConcurrentMap<Long, Session> active = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.concurrent.ScheduledExecutorService heartbeat = java.util.concurrent.Executors
            .newSingleThreadScheduledExecutor();
    @MockitoBean
    private DocumentGenerationIntakeService documentIntake;

    private static String experimentPrefix() {
        String prefix = System.getenv("KNOT_E2E_PREFIX");
        if (prefix == null || !prefix.matches(
                "knot/experiments/recording-release/incremental-e2e-"
                        + "[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}/"
        )) {
            throw new IllegalArgumentException("새 UUID 실험 경로가 필요합니다");
        }
        return prefix;
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry properties) {
        properties.add(
                "knot.transcription.base-url",
                () -> System.getenv("KNOT_E2E_AI_URL")
        );
        properties.add(
                "knot.transcription.token",
                () -> System.getenv("KNOT_E2E_AI_TOKEN")
        );
    }

    @Test
    void twoRecordingsAndPreparedLegacyContention() throws Exception {
        // given
        assertThat(
                e2eS3.getBucketVersioning(builder -> builder.bucket(BUCKET))
                        .status()
        ).isEqualTo(BucketVersioningStatus.ENABLED);
        byte[] pcm = Files.readAllBytes(Path.of(System.getenv("KNOT_E2E_PCM")));
        byte[] webm = Files.readAllBytes(Path.of(System.getenv("KNOT_E2E_WEBM")));
        int rounds = Integer.parseInt(
                System.getenv()
                        .getOrDefault(
                                "KNOT_E2E_ROUNDS",
                                "3"
                        )
        );
        long pause = Long.parseLong(
                System.getenv()
                        .getOrDefault(
                                "KNOT_E2E_FEED_PAUSE_MS",
                                "0"
                        )
        );
        boolean includeLegacy = Boolean.parseBoolean(
                System.getenv()
                        .getOrDefault(
                                "KNOT_E2E_INCLUDE_LEGACY",
                                "true"
                        )
        );
        List<Map<String, Object>> results = new ArrayList<>();
        Map<String, Object> report = new LinkedHashMap<>();
        report.put(
                "scope",
                "real S3 + BE services + Testcontainers PostgreSQL + local native AI; no product browser"
        );
        report.put(
                "feedMode",
                pause == 0
                        ? "accelerated then explicit pre-end catch-up"
                        : "absolute-clock paced PCM fixture; no pre-end catch-up"
        );
        report.put(
                "sourceFrames",
                pcm.length / 2
        );
        report.put(
                "sourceDurationSeconds",
                pcm.length / 32000.0
        );
        report.put(
                "experimentPrefix",
                PREFIX
        );
        report.put(
                "runs",
                results
        );
        report.put(
                "includePreparedLegacyContention",
                includeLegacy
        );
        report.put(
                "execution",
                "running"
        );
        report.put(
                "cleanupStatus",
                "not_started"
        );
        mapper.writeValue(
                Path.of(System.getenv("KNOT_E2E_REPORT"))
                        .toFile(),
                report
        );
        heartbeat.scheduleAtFixedRate(
                () -> active.values()
                        .forEach(session -> {
                            try {
                                recordings.heartbeat(
                                        session.workspace(),
                                        session.member(),
                                        session.id(),
                                        session.control()
                                );
                            } catch (RuntimeException ignored) {
                                /* termination may race the last heartbeat */ }
                        }),
                0,
                5,
                java.util.concurrent.TimeUnit.SECONDS
        );
        try {
            // when
            for (int round = 0; round < rounds; round++) {
                Session first = start(RecordingAudioMode.PCM16_CHUNKS_V1);
                Session second = start(RecordingAudioMode.PCM16_CHUNKS_V1);
                report.put(
                        "currentRound",
                        round + 1
                );
                report.put(
                        "stage",
                        "feeding_pcm"
                );
                int sequence = 0;
                Session legacy = null;
                long feedBegan = System.nanoTime();
                long maxUploadLagMillis = 0;
                long finalUploadLagMillis = 0;
                for (int offset = 0; offset < pcm.length; offset += 160000) {
                    sequence++;
                    long sourceReadyOffsetNanos = pause > 0
                            ? PcmSupplyClock.availableNanos(
                                    Math.min(
                                            pcm.length,
                                            offset + 160000L
                                    )
                            )
                            : 0;
                    if (pause > 0) {
                        long wait = feedBegan + sourceReadyOffsetNanos - System.nanoTime();
                        if (wait > 0) {
                            Thread.sleep(Duration.ofNanos(wait));
                        }
                    }
                    if (includeLegacy && pause > 0 && sequence == 11) {
                        legacy = enqueueLegacy(webm);
                    }
                    byte[] data = Arrays.copyOfRange(
                            pcm,
                            offset,
                            Math.min(
                                    pcm.length,
                                    offset + 160000
                            )
                    );
                    uploadChunk(
                            first,
                            sequence,
                            offset / 2,
                            data
                    );
                    uploadChunk(
                            second,
                            sequence,
                            offset / 2,
                            data
                    );
                    finalUploadLagMillis = Math.max(
                            0,
                            (System.nanoTime() - feedBegan - sourceReadyOffsetNanos) / 1000000
                    );
                    maxUploadLagMillis = Math.max(
                            maxUploadLagMillis,
                            finalUploadLagMillis
                    );
                    report.put(
                            "suppliedChunksPerRecording",
                            sequence
                    );
                    mapper.writeValue(
                            Path.of(System.getenv("KNOT_E2E_REPORT"))
                                    .toFile(),
                            report
                    );
                }
                if (pause == 0) {
                    report.put(
                            "stage",
                            "pre_end_catchup"
                    );
                    mapper.writeValue(
                            Path.of(System.getenv("KNOT_E2E_REPORT"))
                                    .toFile(),
                            report
                    );
                    int fullWindows = (int) Math.max(
                            0,
                            ((pcm.length / 2) - 80000) / 480000
                    );
                    awaitCaughtUp(
                            first.id(),
                            fullWindows
                    );
                    awaitCaughtUp(
                            second.id(),
                            fullWindows
                    );
                }
                if (includeLegacy && legacy == null) {
                    legacy = enqueueLegacy(webm);
                }
                long began = System.nanoTime();
                recordings.end(
                        first.workspace(),
                        first.member(),
                        first.id(),
                        first.control(),
                        new RecordingAudioSealCommand(
                                sequence,
                                pcm.length / 2,
                                List.of()
                        )
                );
                active.remove(first.id());
                long secondBegan = System.nanoTime();
                recordings.end(
                        second.workspace(),
                        second.member(),
                        second.id(),
                        second.control(),
                        new RecordingAudioSealCommand(
                                sequence,
                                pcm.length / 2,
                                List.of()
                        )
                );
                active.remove(second.id());
                report.put(
                        "stage",
                        "waiting_final_results"
                );
                mapper.writeValue(
                        Path.of(System.getenv("KNOT_E2E_REPORT"))
                                .toFile(),
                        report
                );
                Map<Long, Long> completions = new HashMap<>();
                long deadline = System.nanoTime() + Duration.ofSeconds(
                        Long.parseLong(
                                System.getenv()
                                        .getOrDefault(
                                                "KNOT_E2E_TIMEOUT_SECONDS",
                                                "600"
                                        )
                        )
                )
                        .toNanos();
                while (completions.size() < 2 && System.nanoTime() < deadline) {
                    for (Session session : List.of(
                            first,
                            second
                    )) {
                        String state = status(session.id());
                        if ("FAILED".equals(state)) {
                            throw new AssertionError("PCM_TRANSCRIPTION_FAILED");
                        }
                        if ("SUCCEEDED".equals(state)) {
                            completions.putIfAbsent(
                                    session.id(),
                                    System.nanoTime()
                            );
                        }
                    }
                    Thread.sleep(50);
                }
                assertThat(completions).hasSize(2);
                long firstMillis = (completions.get(first.id()) - began) / 1000000;
                long secondMillis = (completions.get(second.id()) - secondBegan) / 1000000;
                results.add(
                        Map.of(
                                "round",
                                round + 1,
                                "firstEndToFinalMillis",
                                firstMillis,
                                "secondEndToFinalMillis",
                                secondMillis,
                                "bothWithin30Seconds",
                                firstMillis <= 30000 && secondMillis <= 30000,
                                "legacyStatusAtPcmCompletion",
                                legacy == null ? "NOT_INCLUDED" : status(legacy.id()),
                                "preEndCatchUp",
                                pause == 0,
                                "feedElapsedMillis",
                                (secondBegan - feedBegan) / 1000000,
                                "feedPauseMillis",
                                pause,
                                "maxUploadLagMillis",
                                pause > 0 ? maxUploadLagMillis : -1,
                                "finalUploadLagMillis",
                                pause > 0 ? finalUploadLagMillis : -1
                        )
                );
                for (Session session : List.of(
                        first,
                        second
                )) {
                    assertThat(
                            jdbc.sql("SELECT COUNT(*) FROM transcripts WHERE recording_session_id=:id")
                                    .param(
                                            "id",
                                            session.id()
                                    )
                                    .query(Long.class)
                                    .single()
                    ).isEqualTo(1);
                    verify(
                            documentIntake,
                            times(1)
                    ).acceptCompletedTranscript(
                            eq(session.workspace()),
                            eq(session.id()),
                            anyLong()
                    );
                }
                mapper.writeValue(
                        Path.of(System.getenv("KNOT_E2E_REPORT"))
                                .toFile(),
                        report
                );
                if (legacy != null) {
                    awaitCompleted(legacy.id());
                }
            }
            // then
            report.put(
                    "execution",
                    "passed"
            );
        } finally {
            report.put(
                    "windowStatuses",
                    jdbc.sql("SELECT status,COUNT(*) AS total FROM recording_transcription_windows GROUP BY status")
                            .query()
                            .listOfRows()
            );
            report.put(
                    "transcriptionStatuses",
                    jdbc.sql("SELECT status,COUNT(*) AS total FROM recording_transcriptions GROUP BY status")
                            .query()
                            .listOfRows()
            );
            report.put(
                    "windowFailureCodes",
                    jdbc.sql(
                            "SELECT failure_code,COUNT(*) AS total FROM recording_transcription_windows "
                                    + "WHERE failure_code IS NOT NULL GROUP BY failure_code"
                    )
                            .query()
                            .listOfRows()
            );
            heartbeat.shutdownNow();
            try {
                preparationWorker.close();
                incrementalWorker.close();
                finalizationWorker.close();
                legacyWorker.close();
                awaitWorkersStopped();
                report.put(
                        "workersStopped",
                        true
                );
                int stableEmptyScans = 0;
                for (int scan = 0; scan < 10 && stableEmptyScans < 2; scan++) {
                    cleanupPrefix();
                    Thread.sleep(1500);
                    stableEmptyScans = remainingVersions() == 0 ? stableEmptyScans + 1 : 0;
                }
                assertThat(stableEmptyScans).isEqualTo(2);
                report.put(
                        "cleanupStableEmptyScans",
                        stableEmptyScans
                );
                report.put(
                        "cleanupRemainingVersions",
                        remainingVersions()
                );
                report.put(
                        "cleanupStatus",
                        "completed"
                );
            } catch (Exception | AssertionError cleanupFailure) {
                report.put(
                        "cleanupStatus",
                        "failed"
                );
                throw cleanupFailure;
            } finally {
                mapper.writeValue(
                        Path.of(System.getenv("KNOT_E2E_REPORT"))
                                .toFile(),
                        report
                );
            }
        }
    }

    private Session enqueueLegacy(byte[] webm) throws Exception {
        Session legacy = start(RecordingAudioMode.FINAL_WEBM_V1);
        recordings.end(
                legacy.workspace(),
                legacy.member(),
                legacy.id(),
                legacy.control()
        );
        RecordingAudioUploadUrlResult upload = uploadUrls.issue(
                legacy.workspace(),
                legacy.member(),
                legacy.id(),
                new RecordingAudioUploadUrlCommand(
                        "audio/webm",
                        webm.length
                )
        );
        put(
                upload.uploadUrl(),
                Map.of(
                        "Content-Type",
                        "audio/webm"
                ),
                webm
        );
        uploadCompletion.complete(
                legacy.workspace(),
                legacy.member(),
                legacy.id(),
                upload.uploadId()
        );
        return legacy;
    }

    private Session start(RecordingAudioMode mode) {
        long member = jdbc.sql("INSERT INTO members(nickname) VALUES ('benchmark') RETURNING id")
                .query(Long.class)
                .single();
        long workspace = jdbc.sql("INSERT INTO workspaces(name,created_at) VALUES ('benchmark',now()) RETURNING id")
                .query(Long.class)
                .single();
        jdbc.sql(
                "INSERT INTO workspace_members(workspace_id,member_id,role,joined_at,last_viewed) "
                        + "VALUES (:w,:m,'OWNER',now(),FALSE)"
        )
                .param(
                        "w",
                        workspace
                )
                .param(
                        "m",
                        member
                )
                .update();
        UUID tab = UUID.randomUUID();
        String token = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefA";
        long id = recordings.start(
                workspace,
                member,
                new RecordingStartCommand(
                        UUID.randomUUID(),
                        tab,
                        token,
                        mode
                )
        )
                .recordingId();
        Session session = new Session(
                workspace,
                member,
                id,
                new RecordingControlCommand(
                        tab,
                        token
                )
        );
        if (mode == RecordingAudioMode.PCM16_CHUNKS_V1) {
            active.put(
                    id,
                    session
            );
        }
        return session;
    }

    private void uploadChunk(
            Session session,
            int sequence,
            long offset,
            byte[] data
    ) throws Exception {
        String checksum = Base64.getEncoder()
                .encodeToString(
                        MessageDigest.getInstance("SHA-256")
                                .digest(data)
                );
        RecordingAudioChunkReservationResult reservation = chunks.reserve(
                session.workspace(),
                session.member(),
                session.id(),
                new RecordingAudioChunkCommand(
                        session.control(),
                        new AudioChunkDescriptor(
                                sequence,
                                offset,
                                data.length / 2,
                                data.length,
                                checksum
                        )
                )
        );
        put(
                reservation.uploadUrl(),
                reservation.requiredHeaders(),
                data
        );
        chunks.complete(
                session.workspace(),
                session.member(),
                session.id(),
                sequence,
                session.control()
        );
    }

    private void put(
            String url,
            Map<String, String> headers,
            byte[] data
    ) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(120));
        headers.forEach(
                (
                        name,
                        value
                ) -> {
                    if (!name.equalsIgnoreCase("content-length") && !name.equalsIgnoreCase("host")) {
                        request.header(
                                name,
                                value
                        );
                    }
                }
        );
        try (HttpClient http = HttpClient.newHttpClient()) {
            assertThat(
                    http.send(
                            request.PUT(HttpRequest.BodyPublishers.ofByteArray(data))
                                    .build(),
                            HttpResponse.BodyHandlers.discarding()
                    )
                            .statusCode()
            ).isEqualTo(200);
        }
    }

    private void awaitCaughtUp(
            long id,
            int count
    ) throws Exception {
        long end = System.nanoTime() + Duration.ofSeconds(
                Long.parseLong(
                        System.getenv()
                                .getOrDefault(
                                        "KNOT_E2E_CATCHUP_TIMEOUT_SECONDS",
                                        "1200"
                                )
                )
        )
                .toNanos();
        while (System.nanoTime() < end) {
            long failed = jdbc
                    .sql(
                            "SELECT COUNT(*) FROM recording_transcription_windows "
                                    + "WHERE recording_id=:id AND status='FAILED'"
                    )
                    .param(
                            "id",
                            id
                    )
                    .query(Long.class)
                    .single();
            assertThat(failed).isZero();
            long completed = jdbc
                    .sql(
                            "SELECT COUNT(*) FROM recording_transcription_windows "
                                    + "WHERE recording_id=:id AND status='SUCCEEDED'"
                    )
                    .param(
                            "id",
                            id
                    )
                    .query(Long.class)
                    .single();
            if (completed >= count) {
                return;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("PRE_END_CATCH_UP_TIMEOUT");
    }
    private void awaitCompleted(long id) throws Exception {
        long end = System.nanoTime() + Duration.ofSeconds(
                Long.parseLong(
                        System.getenv()
                                .getOrDefault(
                                        "KNOT_E2E_CATCHUP_TIMEOUT_SECONDS",
                                        "1200"
                                )
                )
        )
                .toNanos();
        while (System.nanoTime() < end) {
            String state = status(id);
            if ("SUCCEEDED".equals(state)) {
                return;
            }
            if ("FAILED".equals(state)) {
                throw new AssertionError("PREPARED_LEGACY_FAILED");
            }
            Thread.sleep(100);
        }
        throw new AssertionError("PREPARED_LEGACY_TIMEOUT");
    }
    private String status(long id) {
        return jdbc.sql("SELECT status FROM recording_transcriptions WHERE recording_session_id=:id")
                .param(
                        "id",
                        id
                )
                .query(String.class)
                .optional()
                .orElse("PENDING");
    }
    private void awaitWorkersStopped() throws Exception {
        Set<String> names = Set.of(
                "incremental-transcription",
                "audio-preparation",
                "recording-transcription",
                "recording-audio-finalization"
        );
        long deadline = System.nanoTime() + Duration.ofSeconds(45)
                .toNanos();
        while (System.nanoTime() < deadline) {
            boolean alive = Thread.getAllStackTraces()
                    .keySet()
                    .stream()
                    .anyMatch(thread -> thread.isAlive() && names.contains(thread.getName()));
            if (!alive) {
                return;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("BENCHMARK_WORKERS_STILL_ALIVE");
    }
    private int remainingVersions() {
        ListObjectVersionsResponse page = e2eS3.listObjectVersions(
                b -> b.bucket(BUCKET)
                        .prefix(PREFIX)
        );
        return page.versions()
                .size()
                + page.deleteMarkers()
                        .size();
    }
    private void cleanupPrefix() {
        for (int pageNumber = 0; pageNumber < 100; pageNumber++) {
            ListObjectVersionsResponse page = e2eS3.listObjectVersions(
                    b -> b.bucket(BUCKET)
                            .prefix(PREFIX)
            );
            List<ObjectIdentifier> ids = new ArrayList<>();
            page.versions()
                    .forEach(
                            v -> ids.add(
                                    ObjectIdentifier.builder()
                                            .key(v.key())
                                            .versionId(v.versionId())
                                            .build()
                            )
                    );
            page.deleteMarkers()
                    .forEach(
                            v -> ids.add(
                                    ObjectIdentifier.builder()
                                            .key(v.key())
                                            .versionId(v.versionId())
                                            .build()
                            )
                    );
            if (ids.isEmpty()) {
                return;
            }
            for (ObjectIdentifier id : ids) {
                assertThat(id.key()).startsWith(PREFIX);
            }
            assertThat(
                    e2eS3.deleteObjects(
                            b -> b.bucket(BUCKET)
                                    .delete(d -> d.objects(ids))
                    )
                            .errors()
            ).isEmpty();
        }
        throw new AssertionError("EXPERIMENT_PREFIX_CLEANUP_LIMIT");
    }
    private record Session(
            long workspace,
            long member,
            long id,
            RecordingControlCommand control
    ) {
    }

    @TestConfiguration(proxyBeanMethods = false)
    @org.springframework.scheduling.annotation.EnableScheduling
    static class Storage {
        @Bean(destroyMethod = "close")
        S3Client e2eS3() {
            return S3Client.builder()
                    .region(REGION)
                    .build();
        }
        @Bean(destroyMethod = "close")
        S3Presigner e2ePresigner() {
            return S3Presigner.builder()
                    .region(REGION)
                    .build();
        }
        @Bean
        @Primary
        RecordingAudioChunkStorage realChunks(
                S3Client e2eS3,
                S3Presigner e2ePresigner
        ) {
            return new S3RecordingAudioChunkStorage(
                    e2ePresigner,
                    e2eS3,
                    BUCKET,
                    PREFIX,
                    Clock.systemUTC()
            );
        }
        @Bean
        @Primary
        RecordingAudioFinalStorage realFinal(S3Client e2eS3) {
            return new S3RecordingAudioFinalStorage(
                    e2eS3,
                    BUCKET,
                    PREFIX
            );
        }
        @Bean
        @Primary
        RecordingAudioStorage realAudio(
                S3Client e2eS3,
                S3Presigner e2ePresigner
        ) {
            return new S3RecordingAudioStorage(
                    e2ePresigner,
                    e2eS3,
                    BUCKET,
                    PREFIX,
                    Duration.ofMinutes(15)
            );
        }
    }
}
