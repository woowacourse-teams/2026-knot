package com.knot.backend.recording.infrastructure.storage;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.BucketVersioningStatus;
import software.amazon.awssdk.services.s3.model.GetBucketVersioningRequest;
import software.amazon.awssdk.services.s3.model.GetBucketVersioningResponse;
import software.amazon.awssdk.services.s3.model.ListObjectVersionsRequest;
import software.amazon.awssdk.services.s3.model.ListObjectVersionsResponse;
import software.amazon.awssdk.services.s3.model.ObjectVersion;
import software.amazon.awssdk.services.s3.model.DeleteMarkerEntry;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

class S3RecordingAudioPrefixDeletionTest {
    private S3Client client;
    private S3RecordingAudioStorage storage;

    @BeforeEach
    void setUp() {
        client = mock(S3Client.class);
        storage = new S3RecordingAudioStorage(
                null,
                client,
                "bucket",
                "dev/",
                Duration.ofMinutes(15)
        );
        when(client.getBucketVersioning(any(GetBucketVersioningRequest.class))).thenReturn(
                GetBucketVersioningResponse.builder()
                        .status(BucketVersioningStatus.ENABLED)
                        .build()
        );
    }

    @Test
    void deletesEveryVersionAndMarkerAndRechecksEmptyPrefix() {
        // given
        when(client.listObjectVersions(any(ListObjectVersionsRequest.class))).thenReturn(
                ListObjectVersionsResponse.builder()
                        .versions(
                                ObjectVersion.builder()
                                        .key("dev/recordings/1/chunks/1")
                                        .versionId("v1")
                                        .build(),
                                ObjectVersion.builder()
                                        .key("dev/recordings/1/chunks/1")
                                        .versionId("v2")
                                        .build()
                        )
                        .deleteMarkers(
                                DeleteMarkerEntry.builder()
                                        .key("dev/recordings/1/chunks/1")
                                        .versionId("m1")
                                        .build()
                        )
                        .build(),
                ListObjectVersionsResponse.builder()
                        .build()
        );
        // when
        storage.deleteRecordingPrefix("recordings/1/chunks/");
        // then
        verify(
                client,
                times(3)
        ).deleteObject(any(DeleteObjectRequest.class));
        verify(
                client,
                times(2)
        ).listObjectVersions(
                argThat(
                        (ListObjectVersionsRequest request) -> request.prefix()
                                .equals("dev/recordings/1/chunks/") && request.keyMarker() == null
                )
        );
    }

    @ParameterizedTest
    @ValueSource(strings = {"recordings/", "recordings/1", "recordings/0/", "recordings/1/../2/", ""})
    void refusesBroadOrMalformedPrefixes(String prefix) {
        // given
        clearInvocations(client);
        // when
        org.assertj.core.api.ThrowableAssert.ThrowingCallable action = () -> storage.deleteRecordingPrefix(prefix);
        // then
        assertThatThrownBy(action).isInstanceOf(com.knot.backend.recording.domain.RecordingException.class);
        verifyNoInteractions(client);
    }

    @Test
    void rejectsUnexpectedObjectOutsideOwnedPrefix() {
        // given
        when(client.listObjectVersions(any(ListObjectVersionsRequest.class))).thenReturn(
                ListObjectVersionsResponse.builder()
                        .versions(
                                ObjectVersion.builder()
                                        .key("dev/recordings/11/chunks/1")
                                        .versionId("v1")
                                        .build()
                        )
                        .build()
        );
        // when
        org.assertj.core.api.ThrowableAssert.ThrowingCallable action = () -> storage
                .deleteRecordingPrefix("recordings/1/");
        // then
        assertThatThrownBy(action).isInstanceOf(com.knot.backend.recording.domain.RecordingException.class);
        verify(
                client,
                never()
        ).deleteObject(any(DeleteObjectRequest.class));
    }

    @ParameterizedTest
    @ValueSource(ints = {403, 500})
    void listFailureIsNotSuccess(int status) {
        // given
        when(client.listObjectVersions(any(ListObjectVersionsRequest.class))).thenThrow(
                S3Exception.builder()
                        .statusCode(status)
                        .build()
        );
        // when
        org.assertj.core.api.ThrowableAssert.ThrowingCallable action = () -> storage
                .deleteRecordingPrefix("recordings/1/");
        // then
        assertThatThrownBy(action).isInstanceOf(com.knot.backend.recording.domain.RecordingException.class);
    }
    @Test
    void nextSweepDiscoversVersionCreatedAfterPreviouslyEmptyPrefix() {
        // given
        when(client.listObjectVersions(any(ListObjectVersionsRequest.class))).thenReturn(
                ListObjectVersionsResponse.builder()
                        .build()
        );
        storage.deleteRecordingPrefix("recordings/1/");
        when(client.listObjectVersions(any(ListObjectVersionsRequest.class))).thenReturn(
                ListObjectVersionsResponse.builder()
                        .versions(
                                ObjectVersion.builder()
                                        .key("dev/recordings/1/final/late.wav")
                                        .versionId("late-version")
                                        .build()
                        )
                        .build(),
                ListObjectVersionsResponse.builder()
                        .build()
        );
        // when
        storage.deleteRecordingPrefix("recordings/1/");
        // then
        verify(client).deleteObject(
                argThat(
                        (DeleteObjectRequest request) -> request.key()
                                .equals("dev/recordings/1/final/late.wav")
                                && request.versionId()
                                        .equals("late-version")
                )
        );
    }

    @Test
    void failedVersionDeletionIsNeverReportedAsSuccessfulSweep() {
        // given
        when(client.listObjectVersions(any(ListObjectVersionsRequest.class))).thenReturn(
                ListObjectVersionsResponse.builder()
                        .versions(
                                ObjectVersion.builder()
                                        .key("dev/recordings/1/chunks/1")
                                        .versionId("version")
                                        .build()
                        )
                        .build()
        );
        when(client.deleteObject(any(DeleteObjectRequest.class))).thenThrow(
                S3Exception.builder()
                        .statusCode(503)
                        .build()
        );
        // when
        org.assertj.core.api.ThrowableAssert.ThrowingCallable action = () -> storage
                .deleteRecordingPrefix("recordings/1/");
        // then
        assertThatThrownBy(action).isInstanceOf(com.knot.backend.recording.domain.RecordingException.class);
        verify(
                client,
                times(1)
        ).listObjectVersions(any(ListObjectVersionsRequest.class));
    }
}
