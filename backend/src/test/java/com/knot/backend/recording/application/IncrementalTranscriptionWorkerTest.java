package com.knot.backend.recording.application;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.never;

import com.knot.backend.global.config.TranscriptionProperties;
import com.knot.backend.recording.application.dto.result.AiTranscript;
import com.knot.backend.recording.application.dto.result.AiTranscriptionJob;
import com.knot.backend.recording.application.dto.result.AiWindowEvidence;
import com.knot.backend.recording.application.dto.result.TranscriptionWindowAudioSource;
import com.knot.backend.recording.application.dto.result.TranscriptionWindowClaim;
import com.knot.backend.recording.domain.RecordingErrorCode;
import com.knot.backend.recording.domain.model.TranscriptionDecodePolicy;
import com.knot.backend.recording.domain.RecordingException;
import com.knot.backend.recording.domain.model.StoredAudioChunk;
import com.knot.backend.recording.domain.repository.IncrementalTranscriptionQueue;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class IncrementalTranscriptionWorkerTest {
    private final RecordingTranscriptionWindowService state = mock(RecordingTranscriptionWindowService.class);
    private final IncrementalTranscriptionCoordinator coordinator = mock(IncrementalTranscriptionCoordinator.class);
    private final IncrementalTranscriptionQueue queue = mock(IncrementalTranscriptionQueue.class);
    private final TranscriptionWindowAudioAssembler assembler = mock(TranscriptionWindowAudioAssembler.class);
    private final TranscriptionClient client = mock(TranscriptionClient.class);
    private final RecordingAudioStorage storage = mock(RecordingAudioStorage.class);
    private final String manifest = "{\"schemaVersion\":1,\"inputStartFrame\":0,\"inputEndFrame\":16000,\"parts\":[]}";
    private final String hash = "a".repeat(64);
    private final TranscriptionWindowClaim claim = new TranscriptionWindowClaim(
            1,
            2,
            UUID.randomUUID(),
            UUID.randomUUID(),
            1,
            manifest,
            hash,
            TranscriptionDecodePolicy.WITH_VOCABULARY
    );
    private IncrementalTranscriptionWorker worker;

    @BeforeEach
    void prepare() {
        TranscriptionProperties properties = new TranscriptionProperties(
                true,
                URI.create("http://localhost"),
                "test",
                Duration.ofSeconds(1),
                Duration.ofSeconds(1),
                Duration.ofMillis(1),
                Duration.ofMinutes(5),
                Duration.ofSeconds(60),
                Duration.ofSeconds(15),
                Duration.ofSeconds(5)
        );
        worker = new IncrementalTranscriptionWorker(
                state,
                coordinator,
                queue,
                assembler,
                client,
                storage,
                properties,
                new ObjectMapper(),
                Clock.fixed(
                        Instant.parse("2026-10-18T00:00:00Z"),
                        ZoneOffset.UTC
                )
        );
        when(
                queue.findCandidates(
                        any(),
                        anyInt()
                )
        ).thenReturn(
                List.of(
                        new IncrementalTranscriptionQueue.WindowTask(
                                1,
                                2
                        )
                ),
                List.of()
        );
        when(
                state.claim(
                        eq(1L),
                        eq(2L),
                        any(),
                        any()
                )
        ).thenReturn(Optional.of(claim));
        when(
                state.renew(
                        eq(claim),
                        any()
                )
        ).thenReturn(true);
        when(
                state.succeed(
                        eq(claim),
                        eq(1),
                        any()
                )
        ).thenReturn(true);
        when(state.source(claim)).thenReturn(
                Optional.of(
                        new TranscriptionWindowAudioSource(
                                2,
                                1,
                                "pcm-window-v1",
                                manifest,
                                hash,
                                null,
                                null,
                                null,
                                0
                        )
                )
        );
    }

    @AfterEach
    void close() {
        worker.close();
    }

    @Test
    void missingJobMaterializesThenPersistsBeforeDispatch() {
        // given
        TranscriptionWindowAudioSource source = new TranscriptionWindowAudioSource(
                2,
                1,
                "pcm-window-v1",
                manifest,
                hash,
                null,
                null,
                null,
                0
        );
        when(client.get(claim.attemptId())).thenThrow(new RecordingException(RecordingErrorCode.AI_JOB_NOT_FOUND));
        when(state.source(claim)).thenReturn(Optional.of(source));
        when(
                assembler.materialize(
                        eq(source),
                        any()
                )
        ).thenReturn(
                new TranscriptionWindowAudioAssembler.MaterializedWindowAudio(
                        "window.wav",
                        new StoredAudioChunk(
                                true,
                                32044,
                                "audio/wav",
                                "checksum",
                                "v1"
                        )
                )
        );
        when(
                storage.presignDownload(
                        "window.wav",
                        "v1",
                        "audio/wav",
                        32044
                )
        ).thenReturn("https://private");
        when(
                client.submitWindow(
                        eq(claim.attemptId()),
                        eq("https://private"),
                        any()
                )
        ).thenReturn(success(hash));
        // when
        worker.dispatch();
        // then
        verify(
                coordinator,
                timeout(2000)
        ).advance(1);
        org.mockito.InOrder order = inOrder(
                client,
                assembler,
                state,
                queue
        );
        order.verify(client)
                .get(claim.attemptId());
        order.verify(assembler)
                .materialize(
                        eq(source),
                        any()
                );
        order.verify(client)
                .submitWindow(
                        eq(claim.attemptId()),
                        eq("https://private"),
                        argThat(window -> window.decodePolicy() == TranscriptionDecodePolicy.WITH_VOCABULARY)
                );
        order.verify(state)
                .succeed(
                        eq(claim),
                        eq(1),
                        any()
                );
        order.verify(queue)
                .findCandidates(
                        any(),
                        anyInt()
                );
    }

    @Test
    void existingSuccessAvoidsMaterializationAndPost() {
        // given
        when(client.get(claim.attemptId())).thenReturn(success(hash));
        // when
        worker.dispatch();
        // then
        verify(
                state,
                timeout(2000)
        ).succeed(
                eq(claim),
                eq(1),
                any()
        );
        verifyNoInteractions(
                assembler,
                storage
        );
        verify(
                client,
                never()
        ).submitWindow(
                any(),
                any(),
                any()
        );
    }

    @Test
    void getTimeoutRetriesSameAttemptWithoutPosting() {
        // given
        when(client.get(claim.attemptId())).thenThrow(new RecordingException(RecordingErrorCode.AI_TIMEOUT))
                .thenReturn(success(hash));
        // when
        worker.dispatch();
        // then
        verify(
                state,
                timeout(2000)
        ).succeed(
                eq(claim),
                eq(1),
                any()
        );
        verify(
                client,
                times(2)
        ).get(claim.attemptId());
        verify(
                client,
                never()
        ).submitWindow(
                any(),
                any(),
                any()
        );
        verify(
                state,
                never()
        ).defer(
                any(),
                any(),
                any()
        );
        verify(
                state,
                never()
        ).fail(
                any(),
                any()
        );
    }

    @Test
    void busyDefersAndMalformedEvidenceFails() {
        // given
        when(client.get(claim.attemptId())).thenThrow(new RecordingException(RecordingErrorCode.AI_JOB_NOT_FOUND));
        when(
                assembler.materialize(
                        any(),
                        any()
                )
        ).thenReturn(
                new TranscriptionWindowAudioAssembler.MaterializedWindowAudio(
                        "window.wav",
                        new StoredAudioChunk(
                                true,
                                32044,
                                "audio/wav",
                                "checksum",
                                "v1"
                        )
                )
        );
        when(
                storage.presignDownload(
                        any(),
                        any(),
                        any(),
                        org.mockito.ArgumentMatchers.anyLong()
                )
        ).thenReturn("https://private");
        when(
                client.submitWindow(
                        any(),
                        any(),
                        any()
                )
        ).thenThrow(new RecordingException(RecordingErrorCode.AI_BUSY));
        // when
        worker.dispatch();
        // then
        verify(
                state,
                timeout(2000)
        ).defer(
                eq(claim),
                any(),
                eq("AI_BUSY")
        );
        verify(
                state,
                never()
        ).succeed(
                any(),
                anyInt(),
                any()
        );
    }

    @Test
    void mismatchedManifestFailsWithoutAdopting() {
        // given
        when(client.get(claim.attemptId())).thenReturn(success("b".repeat(64)));
        // when
        worker.dispatch();
        // then
        verify(
                state,
                timeout(2000)
        ).fail(
                claim,
                "AI_INVALID_RESPONSE"
        );
        verify(
                state,
                never()
        ).succeed(
                any(),
                anyInt(),
                any()
        );
    }

    @Test
    void lostLeaseStopsBeforeRemoteCall() {
        // given
        when(
                state.renew(
                        eq(claim),
                        any()
                )
        ).thenReturn(false);
        // when
        worker.dispatch();
        // then
        verify(
                state,
                timeout(2000)
        ).expire(
                1,
                2
        );
        verifyNoInteractions(
                client,
                assembler
        );
        verify(
                state,
                never()
        ).succeed(
                any(),
                anyInt(),
                any()
        );
    }

    @Test
    void shutdownDuringRemoteResponseDoesNotAdopt() throws Exception {
        // given
        CountDownLatch entered = new CountDownLatch(1);
        when(client.get(claim.attemptId())).thenAnswer(invocation -> {
            entered.countDown();
            try {
                new CountDownLatch(1).await(
                        5,
                        TimeUnit.SECONDS
                );
            } catch (InterruptedException ignored) {
                // 응답 수신과 종료가 겹친 상황을 재현한다.
            }
            return success(hash);
        });
        worker.dispatch();
        org.assertj.core.api.Assertions.assertThat(
                entered.await(
                        2,
                        TimeUnit.SECONDS
                )
        )
                .isTrue();
        // when
        worker.close();
        // then
        verify(
                state,
                never()
        ).succeed(
                any(),
                anyInt(),
                any()
        );
        verify(
                state,
                never()
        ).fail(
                any(),
                any()
        );
    }

    @Test
    void getNotReadyRetainsSlotAndRetriesWithoutDeferring() {
        // given
        when(client.get(claim.attemptId())).thenThrow(new RecordingException(RecordingErrorCode.AI_NOT_READY))
                .thenReturn(success(hash));
        // when
        worker.dispatch();
        // then
        verify(
                state,
                timeout(2000)
        ).succeed(
                eq(claim),
                eq(1),
                any()
        );
        verify(
                client,
                times(2)
        ).get(claim.attemptId());
        verify(
                state,
                never()
        ).defer(
                any(),
                any(),
                any()
        );
        verify(
                client,
                never()
        ).submitWindow(
                any(),
                any(),
                any()
        );
    }

    private AiTranscriptionJob success(String checksum) {
        AiWindowEvidence evidence = new AiWindowEvidence(
                1,
                "pcm-window-v1",
                checksum,
                0,
                16000,
                "model",
                List.of(),
                List.of()
        );
        return new AiTranscriptionJob(
                claim.attemptId(),
                AiTranscriptionJob.Status.SUCCEEDED,
                null,
                new AiTranscript(
                        1,
                        "{\"transcription\":[]}",
                        List.of(),
                        evidence
                )
        );
    }
}
