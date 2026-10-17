package com.knot.backend.recording.infrastructure.storage;

import com.knot.backend.recording.application.RecordingAudioChunkService;
import com.knot.backend.recording.application.RecordingAudioChunkStorage;
import com.knot.backend.recording.application.RecordingStartService;

import static org.assertj.core.api.Assertions.assertThat;

import com.knot.backend.recording.application.dto.command.RecordingAudioChunkCommand;
import com.knot.backend.recording.application.dto.command.RecordingControlCommand;
import com.knot.backend.recording.application.dto.command.RecordingStartCommand;
import com.knot.backend.recording.application.dto.result.RecordingAudioChunkCompletionResult;
import com.knot.backend.recording.application.dto.result.RecordingAudioChunkReservationResult;
import com.knot.backend.recording.domain.model.AudioChunkDescriptor;
import com.knot.backend.recording.domain.model.RecordingAudioChunkStatus;
import com.knot.backend.recording.domain.model.RecordingAudioMode;
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
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectVersionsRequest;
import software.amazon.awssdk.services.s3.model.ListObjectVersionsResponse;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

@Tag("integration")
@EnabledIfEnvironmentVariable(named = "KNOT_RECORDING_REAL_S3", matches = "true")
@Import({TestcontainersConfiguration.class, RecordingAudioChunkRealS3IntegrationTest.RealStorageConfiguration.class})
@TestApplicationProperties
@SpringBootTest(properties = "recording.audio-chunks.enabled=true")
class RecordingAudioChunkRealS3IntegrationTest {
    private static final String EXPERIMENT_ROOT = "knot/experiments/recording-release/";
    private static final String PREFIX = experimentPrefix();
    private static final String BUCKET = requiredEnvironment("KNOT_RECORDING_TEST_BUCKET");

    @Autowired
    private RecordingStartService startService;
    @Autowired
    private RecordingAudioChunkService chunkService;
    @Autowired
    private JdbcClient jdbcClient;
    @Autowired
    private S3Client realChunkTestClient;

    @Test
    @DisplayName("실제 S3 조건부 업로드와 PostgreSQL ACK는 같은 버전을 유지하며 재요청에 수렴한다")
    void complete_success_realS3AndDatabase() throws Exception {
        // given
        byte[] pcm = Files.readAllBytes(Path.of(requiredEnvironment("KNOT_RECORDING_PCM_FIXTURE")));
        assertThat(pcm.length).isEqualTo(160000);
        String checksum = Base64.getEncoder()
                .encodeToString(
                        MessageDigest.getInstance("SHA-256")
                                .digest(pcm)
                );
        long memberId = createMember();
        long workspaceId = createWorkspace(memberId);

        // when
        Evidence evidence = uploadAndConfirm(
                workspaceId,
                memberId,
                pcm,
                checksum
        );

        // then
        assertThat(evidence.firstStatus()).isEqualTo(200);
        assertThat(evidence.repeatedStatus()).isEqualTo(412);
        assertThat(evidence.first()).isEqualTo(evidence.repeated());
        assertThat(
                evidence.first()
                        .status()
        ).isEqualTo(RecordingAudioChunkStatus.VERIFIED);
        assertThat(
                evidence.first()
                        .verifiedThroughSequence()
        ).isEqualTo(1);
        assertThat(
                evidence.first()
                        .verifiedThroughFrame()
        ).isEqualTo(80000);
        assertThat(
                evidence.first()
                        .verifiedAudioMillis()
        ).isEqualTo(5000);
        assertThat(evidence.persistedChecksum()).isEqualTo(checksum);
        assertThat(evidence.persistedLength()).isEqualTo(pcm.length);
        assertThat(evidence.versionId()).isNotBlank()
                .isNotEqualTo("null");
        assertThat(evidence.repeatedVersionId()).isEqualTo(evidence.versionId());
        assertThat(evidence.downloadedMatches()).isTrue();
    }

    private Evidence uploadAndConfirm(
            long workspaceId,
            long memberId,
            byte[] pcm,
            String checksum
    ) throws Exception {
        UUID tabId = UUID.randomUUID();
        String controlToken = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefA";
        RecordingControlCommand control = new RecordingControlCommand(
                tabId,
                controlToken
        );
        long recordingId = startService.start(
                workspaceId,
                memberId,
                new RecordingStartCommand(
                        UUID.randomUUID(),
                        tabId,
                        controlToken,
                        RecordingAudioMode.PCM16_CHUNKS_V1
                )
        )
                .recordingId();
        RecordingAudioChunkReservationResult reservation = chunkService.reserve(
                workspaceId,
                memberId,
                recordingId,
                new RecordingAudioChunkCommand(
                        control,
                        new AudioChunkDescriptor(
                                1,
                                0,
                                80000,
                                pcm.length,
                                checksum
                        )
                )
        );
        String storageKey = jdbcClient.sql("SELECT storage_key FROM recording_audio_chunks WHERE recording_id = :id")
                .param(
                        "id",
                        recordingId
                )
                .query(String.class)
                .single();
        String exactKey = PREFIX + storageKey;
        Evidence evidence;
        try {
            int firstStatus = put(
                    reservation,
                    pcm
            );
            assertThat(firstStatus).isEqualTo(200);
            RecordingAudioChunkCompletionResult first = chunkService.complete(
                    workspaceId,
                    memberId,
                    recordingId,
                    1,
                    control
            );
            String version = persistedString(
                    recordingId,
                    "storage_version_id"
            );
            byte[] downloaded = realChunkTestClient.getObjectAsBytes(
                    GetObjectRequest.builder()
                            .bucket(BUCKET)
                            .key(exactKey)
                            .versionId(version)
                            .build()
            )
                    .asByteArray();
            int repeatedStatus = put(
                    reservation,
                    pcm
            );
            RecordingAudioChunkCompletionResult repeated = chunkService.complete(
                    workspaceId,
                    memberId,
                    recordingId,
                    1,
                    control
            );
            String repeatedVersion = realChunkTestClient.headObject(
                    builder -> builder.bucket(BUCKET)
                            .key(exactKey)
            )
                    .versionId();
            evidence = new Evidence(
                    firstStatus,
                    repeatedStatus,
                    first,
                    repeated,
                    persistedString(
                            recordingId,
                            "checksum_sha256"
                    ),
                    jdbcClient.sql("SELECT byte_length FROM recording_audio_chunks WHERE recording_id = :id")
                            .param(
                                    "id",
                                    recordingId
                            )
                            .query(Long.class)
                            .single(),
                    version,
                    repeatedVersion,
                    MessageDigest.isEqual(
                            pcm,
                            downloaded
                    )
            );
        } finally {
            deleteExactKeyVersions(exactKey);
        }
        assertThat(countExactKeyVersions(exactKey)).isZero();
        return evidence;
    }

    private int put(
            RecordingAudioChunkReservationResult reservation,
            byte[] pcm
    ) {
        try (HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build()) {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(reservation.uploadUrl()))
                    .timeout(Duration.ofSeconds(20));
            reservation.requiredHeaders()
                    .forEach(builder::header);
            return client.send(
                    builder.PUT(HttpRequest.BodyPublishers.ofByteArray(pcm))
                            .build(),
                    HttpResponse.BodyHandlers.discarding()
            )
                    .statusCode();
        } catch (Exception exception) {
            if (exception instanceof InterruptedException) {
                Thread.currentThread()
                        .interrupt();
            }
            // 서명 URL이 포함될 수 있는 네트워크 예외 원문은 테스트 출력에 노출하지 않는다.
            throw new AssertionError("실제 S3 PUT 요청을 완료하지 못했습니다");
        }
    }

    private String persistedString(
            long recordingId,
            String column
    ) {
        return jdbcClient.sql("SELECT " + column + " FROM recording_audio_chunks WHERE recording_id = :id")
                .param(
                        "id",
                        recordingId
                )
                .query(String.class)
                .single();
    }

    private void deleteExactKeyVersions(String exactKey) {
        for (ListObjectVersionsResponse page : realChunkTestClient.listObjectVersionsPaginator(
                ListObjectVersionsRequest.builder()
                        .bucket(BUCKET)
                        .prefix(exactKey)
                        .build()
        )) {
            page.versions()
                    .stream()
                    .filter(version -> exactKey.equals(version.key()))
                    .forEach(
                            version -> deleteVersion(
                                    exactKey,
                                    version.versionId()
                            )
                    );
            page.deleteMarkers()
                    .stream()
                    .filter(marker -> exactKey.equals(marker.key()))
                    .forEach(
                            marker -> deleteVersion(
                                    exactKey,
                                    marker.versionId()
                            )
                    );
        }
    }

    private void deleteVersion(
            String exactKey,
            String versionId
    ) {
        realChunkTestClient.deleteObject(
                DeleteObjectRequest.builder()
                        .bucket(BUCKET)
                        .key(exactKey)
                        .versionId(versionId)
                        .build()
        );
    }

    private long countExactKeyVersions(String exactKey) {
        long count = 0;
        for (ListObjectVersionsResponse page : realChunkTestClient.listObjectVersionsPaginator(
                ListObjectVersionsRequest.builder()
                        .bucket(BUCKET)
                        .prefix(exactKey)
                        .build()
        )) {
            count += page.versions()
                    .stream()
                    .filter(version -> exactKey.equals(version.key()))
                    .count();
            count += page.deleteMarkers()
                    .stream()
                    .filter(marker -> exactKey.equals(marker.key()))
                    .count();
        }
        return count;
    }

    private long createMember() {
        return jdbcClient.sql("INSERT INTO members (nickname) VALUES ('real-s3-probe') RETURNING id")
                .query(Long.class)
                .single();
    }

    private long createWorkspace(long memberId) {
        long workspaceId = jdbcClient
                .sql("INSERT INTO workspaces (name, created_at) VALUES ('실험 팀', now()) RETURNING id")
                .query(Long.class)
                .single();
        jdbcClient.sql("""
                INSERT INTO workspace_members (workspace_id, member_id, role, joined_at, last_viewed)
                VALUES (:workspaceId, :memberId, 'OWNER', now(), FALSE)
                """)
                .param(
                        "workspaceId",
                        workspaceId
                )
                .param(
                        "memberId",
                        memberId
                )
                .update();
        return workspaceId;
    }

    private static String experimentPrefix() {
        String configured = requiredEnvironment("KNOT_RECORDING_TEST_PREFIX");
        if (!configured.startsWith(EXPERIMENT_ROOT) || configured.contains("..")
                || !configured.matches("knot/experiments/recording-release/[A-Za-z0-9/_-]+")) {
            throw new IllegalArgumentException("실험용 S3 경로만 사용할 수 있습니다");
        }
        return configured + (configured.endsWith("/") ? "" : "/") + UUID.randomUUID() + "/";
    }

    private static String requiredEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("실제 S3 검증 환경 설정이 필요합니다");
        }
        return value;
    }

    private record Evidence(
            int firstStatus,
            int repeatedStatus,
            RecordingAudioChunkCompletionResult first,
            RecordingAudioChunkCompletionResult repeated,
            String persistedChecksum,
            long persistedLength,
            String versionId,
            String repeatedVersionId,
            boolean downloadedMatches
    ) {
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class RealStorageConfiguration {
        @Bean(destroyMethod = "close")
        S3Client realChunkTestClient() {
            return S3Client.builder()
                    .region(Region.AP_NORTHEAST_2)
                    .build();
        }

        @Bean(destroyMethod = "close")
        S3Presigner realChunkTestPresigner() {
            return S3Presigner.builder()
                    .region(Region.AP_NORTHEAST_2)
                    .build();
        }

        @Bean
        @Primary
        RecordingAudioChunkStorage realChunkTestStorage(
                S3Client realChunkTestClient,
                S3Presigner realChunkTestPresigner
        ) {
            return new S3RecordingAudioChunkStorage(
                    realChunkTestPresigner,
                    realChunkTestClient,
                    BUCKET,
                    PREFIX,
                    Clock.systemUTC()
            );
        }
    }
}
