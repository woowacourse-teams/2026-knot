package com.knot.backend.recording.infrastructure.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.knot.backend.recording.domain.RecordingException;
import com.knot.backend.recording.domain.model.AudioManifestPart;
import com.knot.backend.recording.domain.model.StoredAudioChunk;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.AbortableInputStream;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.ChecksumMode;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

class S3RecordingAudioFinalStorageTest {
    private static final String CHECKSUM = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=";

    @TempDir
    Path directory;
    private final S3Client client = mock(S3Client.class);
    private final S3RecordingAudioFinalStorage storage = new S3RecordingAudioFinalStorage(
            client,
            "bucket",
            "prefix/"
    );

    @Test
    void openChunk_getsExactVersionWithChecksum() throws Exception {
        // given
        AudioManifestPart part = new AudioManifestPart(
                1,
                0,
                1,
                "chunk",
                CHECKSUM,
                "version-7"
        );
        ResponseInputStream<GetObjectResponse> response = new ResponseInputStream<>(
                GetObjectResponse.builder()
                        .build(),
                AbortableInputStream.create(new java.io.ByteArrayInputStream(new byte[]{1, 2}))
        );
        when(client.getObject(any(GetObjectRequest.class))).thenReturn(response);

        // when
        java.io.InputStream result = storage.openChunk(part);

        // then
        try (result) {
            assertThat(result.readAllBytes()).containsExactly(
                    1,
                    2
            );
        }
        ArgumentCaptor<GetObjectRequest> request = ArgumentCaptor.forClass(GetObjectRequest.class);
        verify(client).getObject(request.capture());
        assertThat(
                request.getValue()
                        .versionId()
        ).isEqualTo("version-7");
        assertThat(
                request.getValue()
                        .key()
        ).isEqualTo("prefix/chunk");
        assertThat(
                request.getValue()
                        .checksumMode()
        ).isEqualTo(ChecksumMode.ENABLED);
    }

    @Test
    void uploadFinal_putsConditionalWavAndVerifiesHead() throws Exception {
        // given
        Path file = Files.write(
                directory.resolve("test.wav"),
                new byte[44]
        );
        when(client.headObject(any(HeadObjectRequest.class))).thenReturn(head());

        // when
        StoredAudioChunk result = storage.uploadFinal(
                "final.wav",
                file,
                44,
                CHECKSUM
        );

        // then
        assertThat(result.versionId()).isEqualTo("v-final");
        ArgumentCaptor<PutObjectRequest> request = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(client).putObject(
                request.capture(),
                any(RequestBody.class)
        );
        assertThat(
                request.getValue()
                        .ifNoneMatch()
        ).isEqualTo("*");
        assertThat(
                request.getValue()
                        .checksumSHA256()
        ).isEqualTo(CHECKSUM);
        assertThat(
                request.getValue()
                        .contentType()
        ).isEqualTo("audio/wav");
        assertThat(
                request.getValue()
                        .contentLength()
        ).isEqualTo(44);
        assertThat(
                request.getValue()
                        .key()
        ).isEqualTo("prefix/final.wav");
    }

    @Test
    void uploadFinal_recoversExistingIdenticalObjectAfter412() throws Exception {
        // given
        Path file = Files.write(
                directory.resolve("test.wav"),
                new byte[44]
        );
        when(
                client.putObject(
                        any(PutObjectRequest.class),
                        any(RequestBody.class)
                )
        ).thenThrow(
                S3Exception.builder()
                        .statusCode(412)
                        .build()
        );
        when(client.headObject(any(HeadObjectRequest.class))).thenReturn(head());

        // when
        StoredAudioChunk result = storage.uploadFinal(
                "final.wav",
                file,
                44,
                CHECKSUM
        );

        // then
        assertThat(result.versionId()).isEqualTo("v-final");
    }

    @Test
    void uploadFinal_rejectsExistingDifferentObjectAfter412() throws Exception {
        // given
        Path file = Files.write(
                directory.resolve("test.wav"),
                new byte[44]
        );
        when(
                client.putObject(
                        any(PutObjectRequest.class),
                        any(RequestBody.class)
                )
        ).thenThrow(
                S3Exception.builder()
                        .statusCode(412)
                        .build()
        );
        when(client.headObject(any(HeadObjectRequest.class))).thenReturn(
                head().toBuilder()
                        .checksumSHA256("different")
                        .build()
        );

        // when
        Throwable failure = catchThrowable(
                () -> storage.uploadFinal(
                        "final.wav",
                        file,
                        44,
                        CHECKSUM
                )
        );

        // then
        assertThat(failure).isInstanceOf(RecordingException.class);
    }

    @ParameterizedTest
    @ValueSource(ints = {403, 500, 503})
    void findStoredFinal_doesNotConvertStorageFailureToMissing(int status) {
        // given
        when(client.headObject(any(HeadObjectRequest.class))).thenThrow(
                S3Exception.builder()
                        .statusCode(status)
                        .build()
        );

        // when
        Throwable failure = catchThrowable(() -> storage.findStoredFinal("final.wav"));

        // then
        assertThat(failure).isInstanceOf(RecordingException.class);
    }

    @Test
    void findStoredFinal_only404MeansMissing() {
        // given
        when(client.headObject(any(HeadObjectRequest.class))).thenThrow(
                S3Exception.builder()
                        .statusCode(404)
                        .build()
        );

        // when
        StoredAudioChunk result = storage.findStoredFinal("final.wav");

        // then
        assertThat(result.exists()).isFalse();
    }

    @Test
    void openChunk_marksAccessDeniedAsPermanentFailure() {
        // given
        AudioManifestPart part = new AudioManifestPart(
                1,
                0,
                1,
                "chunk",
                CHECKSUM,
                "v1"
        );
        when(client.getObject(any(GetObjectRequest.class))).thenThrow(
                S3Exception.builder()
                        .statusCode(403)
                        .build()
        );

        // when
        Throwable failure = catchThrowable(() -> storage.openChunk(part));

        // then
        assertThat(failure).isInstanceOf(com.knot.backend.recording.domain.AudioStorageAccessDeniedException.class);
    }

    private HeadObjectResponse head() {
        return HeadObjectResponse.builder()
                .contentLength(44L)
                .contentType("audio/wav")
                .checksumSHA256(CHECKSUM)
                .versionId("v-final")
                .build();
    }
}
