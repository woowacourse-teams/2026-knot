package com.knot.backend.recording.infrastructure.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.knot.backend.recording.application.RecordingAudioChunkStorage;
import com.knot.backend.recording.application.dto.result.PresignedAudioChunk;
import com.knot.backend.recording.domain.RecordingErrorCode;
import com.knot.backend.recording.domain.RecordingException;
import com.knot.backend.recording.domain.model.StoredAudioChunk;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.ChecksumMode;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

class S3RecordingAudioChunkStorageTest {
    private static final Instant NOW = Instant.parse("2026-10-17T10:00:00Z");
    private static final String CHECKSUM = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=";

    private final S3Client client = mock(S3Client.class);
    private final S3Presigner presigner = mock(S3Presigner.class);
    private S3RecordingAudioChunkStorage storage;

    @BeforeEach
    void setUp() {
        storage = new S3RecordingAudioChunkStorage(
                presigner,
                client,
                "test-audio",
                "knot/dev/",
                Clock.fixed(
                        NOW,
                        ZoneOffset.UTC
                )
        );
    }

    @Test
    @DisplayName("실제 SDK 서명은 크기·형식·SHA256·새 객체 조건을 고정하고 브라우저가 보낼 헤더를 반환한다")
    void presignUpload_success_actualSdkSignature() {
        // given
        Instant upperBound = Instant.now()
                .plusSeconds(120);
        try (S3Presigner actualPresigner = actualPresigner()) {
            S3RecordingAudioChunkStorage actualStorage = new S3RecordingAudioChunkStorage(
                    actualPresigner,
                    client,
                    "test-audio",
                    "knot/dev/",
                    Clock.systemUTC()
            );

            // when
            PresignedAudioChunk signed = actualStorage.presignUpload(
                    "recordings/7/chunks/1",
                    160000,
                    CHECKSUM,
                    upperBound
            );

            // then
            URI uri = URI.create(signed.uploadUrl());
            String signedHeaders = queryParameter(
                    uri,
                    "X-Amz-SignedHeaders"
            );
            assertThat(uri.getPath()).isEqualTo("/test-audio/knot/dev/recordings/7/chunks/1");
            assertThat(signedHeaders.split(";")).contains(
                    "content-length",
                    "content-type",
                    "host",
                    "if-none-match",
                    "x-amz-checksum-sha256"
            );
            assertThat(signed.requiredHeaders()).containsEntry(
                    "content-type",
                    "application/octet-stream"
            )
                    .containsEntry(
                            "if-none-match",
                            "*"
                    )
                    .containsEntry(
                            "x-amz-checksum-sha256",
                            CHECKSUM
                    )
                    .doesNotContainKeys(
                            "host",
                            "content-length"
                    );
            assertThat(signed.expiresAt()).isBeforeOrEqualTo(upperBound)
                    .isAfter(Instant.now());
            assertThat(signed.toString()).doesNotContain(signed.uploadUrl())
                    .doesNotContain(CHECKSUM);
            verifyNoInteractions(client);
        }
    }

    @Test
    @DisplayName("서명 기간에서 여유 1초를 빼고 SDK가 실제 발급한 만료 시각을 응답한다")
    void presignUpload_success_boundsExpiration() {
        // given
        Instant actualExpiry = NOW.plusSeconds(119);
        PresignedPutObjectRequest signed = signedRequest(actualExpiry);
        when(presigner.presignPutObject(any(PutObjectPresignRequest.class))).thenReturn(signed);

        // when
        PresignedAudioChunk result = storage.presignUpload(
                "recordings/7/chunks/1",
                160000,
                CHECKSUM,
                NOW.plusSeconds(120)
        );

        // then
        ArgumentCaptor<PutObjectPresignRequest> request = ArgumentCaptor.forClass(PutObjectPresignRequest.class);
        verify(presigner).presignPutObject(request.capture());
        assertThat(
                request.getValue()
                        .signatureDuration()
        ).isEqualTo(Duration.ofSeconds(119));
        assertThat(
                request.getValue()
                        .putObjectRequest()
                        .contentLength()
        ).isEqualTo(160000);
        assertThat(result.expiresAt()).isEqualTo(actualExpiry);
        verifyNoInteractions(client);
    }

    @ParameterizedTest
    @ValueSource(longs = {-1, 0, 1})
    @DisplayName("DB의 발급 상한이 지났거나 서명 여유가 없으면 저장소에 요청하지 않는다")
    void presignUpload_failure_expiredUpperBound(long remainingSeconds) {
        // given
        Instant upperBound = NOW.plusSeconds(remainingSeconds);

        // when
        Throwable failure = catchThrowable(
                () -> storage.presignUpload(
                        "recordings/7/chunks/1",
                        160000,
                        CHECKSUM,
                        upperBound
                )
        );

        // then
        assertUnavailable(failure);
        verifyNoInteractions(
                presigner,
                client
        );
    }

    @ParameterizedTest
    @ValueSource(longs = {0, 121})
    @DisplayName("SDK 시계 차이로 만료됐거나 DB 상한을 넘는 서명이 생기면 URL을 반환하지 않는다")
    void presignUpload_failure_expirationOutsideReservation(long expirationSeconds) {
        // given
        PresignedPutObjectRequest signed = signedRequest(NOW.plusSeconds(expirationSeconds));
        when(presigner.presignPutObject(any(PutObjectPresignRequest.class))).thenReturn(signed);

        // when
        Throwable failure = catchThrowable(
                () -> storage.presignUpload(
                        "recordings/7/chunks/1",
                        160000,
                        CHECKSUM,
                        NOW.plusSeconds(120)
                )
        );

        // then
        assertUnavailable(failure);
    }

    @Test
    @DisplayName("서명 도중 자격 증명이나 SDK 오류가 발생하면 저장소 사용 불가로 알린다")
    void presignUpload_failure_sdkError() {
        // given
        when(presigner.presignPutObject(any(PutObjectPresignRequest.class)))
                .thenThrow(SdkClientException.create("테스트 SDK 오류"));

        // when
        Throwable failure = catchThrowable(
                () -> storage.presignUpload(
                        "recordings/7/chunks/1",
                        160000,
                        CHECKSUM,
                        NOW.plusSeconds(120)
                )
        );

        // then
        assertUnavailable(failure);
    }

    @Test
    @DisplayName("HEAD는 체크섬 모드를 켜고 같은 접두사의 객체 크기·형식·SHA256·버전을 반환한다")
    void findStoredObject_success_checksumAndVersion() {
        // given
        when(client.headObject(any(HeadObjectRequest.class))).thenReturn(
                HeadObjectResponse.builder()
                        .contentLength(160000L)
                        .contentType("application/octet-stream")
                        .checksumSHA256(CHECKSUM)
                        .versionId("immutable-version")
                        .build()
        );

        // when
        StoredAudioChunk result = storage.findStoredObject("recordings/7/chunks/1");

        // then
        ArgumentCaptor<HeadObjectRequest> request = ArgumentCaptor.forClass(HeadObjectRequest.class);
        verify(client).headObject(request.capture());
        assertThat(
                request.getValue()
                        .bucket()
        ).isEqualTo("test-audio");
        assertThat(
                request.getValue()
                        .key()
        ).isEqualTo("knot/dev/recordings/7/chunks/1");
        assertThat(
                request.getValue()
                        .checksumMode()
        ).isEqualTo(ChecksumMode.ENABLED);
        assertThat(result).isEqualTo(
                new StoredAudioChunk(
                        true,
                        160000,
                        "application/octet-stream",
                        CHECKSUM,
                        "immutable-version"
                )
        );
    }

    @Test
    @DisplayName("HEAD에 체크섬이나 버전이 없으면 값을 만들어내지 않고 도메인 검증에 전달한다")
    void findStoredObject_success_missingVerificationMetadata() {
        // given
        when(client.headObject(any(HeadObjectRequest.class))).thenReturn(
                HeadObjectResponse.builder()
                        .build()
        );

        // when
        StoredAudioChunk result = storage.findStoredObject("recordings/7/chunks/1");

        // then
        assertThat(result.exists()).isTrue();
        assertThat(result.byteLength()).isEqualTo(-1);
        assertThat(result.checksumSha256()).isNull();
        assertThat(result.versionId()).isNull();
    }

    @Test
    @DisplayName("HEAD 404만 객체가 아직 업로드되지 않은 것으로 해석한다")
    void findStoredObject_success_missingObject() {
        // given
        when(client.headObject(any(HeadObjectRequest.class))).thenThrow(
                S3Exception.builder()
                        .statusCode(404)
                        .build()
        );

        // when
        StoredAudioChunk result = storage.findStoredObject("recordings/7/chunks/1");

        // then
        assertThat(result).isEqualTo(StoredAudioChunk.missing());
    }

    @ParameterizedTest
    @ValueSource(ints = {403, 500, 503})
    @DisplayName("HEAD 권한·저장소 오류를 파일 미존재로 바꾸지 않고 저장소 사용 불가로 알린다")
    void findStoredObject_failure_storageError(int status) {
        // given
        when(client.headObject(any(HeadObjectRequest.class))).thenThrow(
                S3Exception.builder()
                        .statusCode(status)
                        .build()
        );

        // when
        Throwable failure = catchThrowable(() -> storage.findStoredObject("recordings/7/chunks/1"));

        // then
        assertUnavailable(failure);
    }

    @Test
    @DisplayName("HEAD 전송 실패는 객체가 없는 상태와 구분한다")
    void findStoredObject_failure_sdkError() {
        // given
        when(client.headObject(any(HeadObjectRequest.class))).thenThrow(SdkClientException.create("테스트 전송 오류"));

        // when
        Throwable failure = catchThrowable(() -> storage.findStoredObject("recordings/7/chunks/1"));

        // then
        assertUnavailable(failure);
    }

    @Test
    @DisplayName("설정이 비어 있으면 애플리케이션은 기동하고 조각 저장 요청만 거절한다")
    void recordingAudioChunkStorage_failure_unconfigured() {
        // given
        RecordingAudioStorageProperties properties = new RecordingAudioStorageProperties(
                "",
                "",
                "",
                "",
                "",
                "",
                Duration.ofMinutes(15)
        );
        RecordingAudioChunkStorage unavailable = new RecordingAudioStorageConfig().recordingAudioChunkStorage(
                properties,
                Clock.systemUTC()
        );

        // when
        Throwable failure = catchThrowable(
                () -> unavailable.presignUpload(
                        "recordings/7/chunks/1",
                        160000,
                        CHECKSUM,
                        NOW.plusSeconds(120)
                )
        );

        // then
        assertUnavailable(failure);
    }

    private void assertUnavailable(Throwable failure) {
        assertThat(failure).isInstanceOf(RecordingException.class)
                .extracting("errorCode")
                .isEqualTo(RecordingErrorCode.AUDIO_STORAGE_UNAVAILABLE);
    }

    private PresignedPutObjectRequest signedRequest(Instant expiration) {
        PresignedPutObjectRequest signed = mock(PresignedPutObjectRequest.class);
        when(signed.expiration()).thenReturn(expiration);
        try {
            when(signed.url()).thenReturn(
                    URI.create("https://example.test/redacted")
                            .toURL()
            );
        } catch (java.net.MalformedURLException exception) {
            throw new IllegalStateException(
                    "테스트 URL을 생성할 수 없습니다",
                    exception
            );
        }
        when(signed.signedHeaders()).thenReturn(
                Map.of(
                        "content-type",
                        java.util.List.of("application/octet-stream")
                )
        );
        return signed;
    }

    private String queryParameter(
            URI uri,
            String name
    ) {
        return Arrays.stream(
                uri.getRawQuery()
                        .split("&")
        )
                .map(
                        part -> part.split(
                                "=",
                                2
                        )
                )
                .filter(parts -> name.equals(parts[0]))
                .map(
                        parts -> URLDecoder.decode(
                                parts[1],
                                StandardCharsets.UTF_8
                        )
                )
                .findFirst()
                .orElseThrow();
    }

    private S3Presigner actualPresigner() {
        return S3Presigner.builder()
                .endpointOverride(URI.create("https://s3.ap-northeast-2.amazonaws.com"))
                .region(Region.AP_NORTHEAST_2)
                .credentialsProvider(
                        StaticCredentialsProvider.create(
                                AwsBasicCredentials.create(
                                        "test-access-key",
                                        "test-secret-key"
                                )
                        )
                )
                .serviceConfiguration(
                        S3Configuration.builder()
                                .pathStyleAccessEnabled(true)
                                .build()
                )
                .build();
    }
}
