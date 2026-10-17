package com.knot.backend.recording.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verifyNoInteractions;

import com.knot.backend.recording.application.dto.result.AudioFinalizationClaim;
import com.knot.backend.recording.application.dto.result.AudioFinalizationInput;
import com.knot.backend.recording.application.dto.result.FinalizedAudioFile;
import com.knot.backend.recording.domain.RecordingErrorCode;
import com.knot.backend.recording.domain.RecordingException;
import com.knot.backend.recording.domain.repository.RecordingAudioFinalizationRepository;
import com.knot.backend.recording.domain.model.AudioManifestPart;
import com.knot.backend.recording.domain.model.StoredAudioChunk;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RecordingAudioFinalizationWorkerTest {
    @TempDir
    Path directory;
    private final RecordingAudioFinalizationTransaction transaction = mock(RecordingAudioFinalizationTransaction.class);
    private final RecordingAudioFinalStorage storage = mock(RecordingAudioFinalStorage.class);
    private final RecordingAudioAssembler assembler = mock(RecordingAudioAssembler.class);
    private final RecordingAudioChunkStorage chunks = mock(RecordingAudioChunkStorage.class);
    private RecordingAudioFinalizationWorker worker;
    private AudioFinalizationClaim claim;
    private AudioFinalizationInput input;
    private Path file;

    @BeforeEach
    void setUp() throws Exception {
        worker = new RecordingAudioFinalizationWorker(
                transaction,
                mock(RecordingAudioFinalizationRepository.class),
                chunks,
                storage,
                assembler,
                Clock.systemUTC()
        );
        claim = new AudioFinalizationClaim(
                1,
                UUID.randomUUID(),
                1
        );
        input = new AudioFinalizationInput(
                "final.wav",
                1,
                List.of(
                        new AudioManifestPart(
                                1,
                                0,
                                1,
                                "chunk",
                                "hash",
                                "v1"
                        )
                )
        );
        file = Files.write(
                directory.resolve("result.wav"),
                new byte[46]
        );
        when(transaction.findChunks(claim)).thenReturn(List.of());
    }

    @AfterEach
    void close() {
        worker.close();
    }

    @Test
    void process_adoptsExistingMatchingFinalWithoutSecondPut() {
        // given
        when(
                transaction.reconcile(
                        org.mockito.ArgumentMatchers.eq(claim),
                        org.mockito.ArgumentMatchers.eq(Map.of()),
                        org.mockito.ArgumentMatchers.any()
                )
        ).thenReturn(Optional.of(input));
        StoredAudioChunk stored = new StoredAudioChunk(
                true,
                46,
                "audio/wav",
                "hash",
                "final-v1"
        );
        when(storage.findStoredFinal("final.wav")).thenReturn(stored);
        when(
                assembler.assemble(
                        any(),
                        org.mockito.ArgumentMatchers.eq(1L),
                        any()
                )
        ).thenReturn(
                new FinalizedAudioFile(
                        file,
                        46,
                        "hash"
                )
        );

        // when
        worker.process(claim);

        // then
        verify(transaction).adopt(
                claim,
                stored
        );
        verify(
                storage,
                never()
        ).uploadFinal(
                any(),
                any(),
                org.mockito.ArgumentMatchers.anyLong(),
                any()
        );
        assertThat(file).doesNotExist();
    }

    @Test
    void process_uploadsAndCleansLocalFileBeforeAdopting() {
        // given
        when(
                transaction.reconcile(
                        org.mockito.ArgumentMatchers.eq(claim),
                        org.mockito.ArgumentMatchers.eq(Map.of()),
                        org.mockito.ArgumentMatchers.any()
                )
        ).thenReturn(Optional.of(input));
        when(storage.findStoredFinal("final.wav")).thenReturn(StoredAudioChunk.missing());
        when(
                assembler.assemble(
                        any(),
                        org.mockito.ArgumentMatchers.eq(1L),
                        any()
                )
        ).thenReturn(
                new FinalizedAudioFile(
                        file,
                        46,
                        "hash"
                )
        );
        StoredAudioChunk stored = new StoredAudioChunk(
                true,
                46,
                "audio/wav",
                "hash",
                "final-v1"
        );
        when(
                storage.uploadFinal(
                        "final.wav",
                        file,
                        46,
                        "hash"
                )
        ).thenReturn(stored);

        // when
        worker.process(claim);

        // then
        verify(transaction).adopt(
                claim,
                stored
        );
        assertThat(file).doesNotExist();
    }

    @Test
    void process_preservesOriginalsAndRetriesTransientPutFailure() {
        // given
        when(
                transaction.reconcile(
                        org.mockito.ArgumentMatchers.eq(claim),
                        org.mockito.ArgumentMatchers.eq(Map.of()),
                        org.mockito.ArgumentMatchers.any()
                )
        ).thenReturn(Optional.of(input));
        when(storage.findStoredFinal("final.wav")).thenReturn(StoredAudioChunk.missing());
        when(
                assembler.assemble(
                        any(),
                        org.mockito.ArgumentMatchers.eq(1L),
                        any()
                )
        ).thenReturn(
                new FinalizedAudioFile(
                        file,
                        46,
                        "hash"
                )
        );
        when(
                storage.uploadFinal(
                        "final.wav",
                        file,
                        46,
                        "hash"
                )
        ).thenThrow(new RecordingException(RecordingErrorCode.AUDIO_STORAGE_UNAVAILABLE));

        // when
        worker.process(claim);

        // then
        verify(transaction).fail(
                claim,
                "AUDIO_STORAGE_UNAVAILABLE",
                true
        );
        verify(
                transaction,
                never()
        ).adopt(
                any(),
                any()
        );
        assertThat(file).doesNotExist();
    }

    @Test
    void process_rejectsDifferentFinalChecksumPermanently() {
        // given
        when(
                transaction.reconcile(
                        org.mockito.ArgumentMatchers.eq(claim),
                        org.mockito.ArgumentMatchers.eq(Map.of()),
                        org.mockito.ArgumentMatchers.any()
                )
        ).thenReturn(Optional.of(input));
        when(storage.findStoredFinal("final.wav")).thenReturn(
                new StoredAudioChunk(
                        true,
                        46,
                        "audio/wav",
                        "wrong",
                        "v2"
                )
        );
        when(
                assembler.assemble(
                        any(),
                        org.mockito.ArgumentMatchers.eq(1L),
                        any()
                )
        ).thenReturn(
                new FinalizedAudioFile(
                        file,
                        46,
                        "hash"
                )
        );

        // when
        worker.process(claim);

        // then
        verify(transaction).fail(
                claim,
                "AUDIO_CHUNK_MISMATCH",
                false
        );
        verify(
                transaction,
                never()
        ).adopt(
                any(),
                any()
        );
        assertThat(file).doesNotExist();
    }

    @Test
    void process_frozenRetrySkipsAllReservedObjectHeads() {
        // given
        when(transaction.frozenInput(claim)).thenReturn(Optional.of(input));
        StoredAudioChunk stored = new StoredAudioChunk(
                true,
                46,
                "audio/wav",
                "hash",
                "final-v1"
        );
        when(storage.findStoredFinal("final.wav")).thenReturn(stored);
        when(
                assembler.assemble(
                        any(),
                        org.mockito.ArgumentMatchers.eq(1L),
                        any()
                )
        ).thenReturn(
                new FinalizedAudioFile(
                        file,
                        46,
                        "hash"
                )
        );

        // when
        worker.process(claim);

        // then
        verifyNoInteractions(chunks);
        verify(
                transaction,
                never()
        ).findChunks(any());
        verify(transaction).adopt(
                claim,
                stored
        );
    }

    @Test
    void process_doesNotAssembleBeforeCutoffOrWithoutContent() {
        // given
        when(
                transaction.reconcile(
                        org.mockito.ArgumentMatchers.eq(claim),
                        org.mockito.ArgumentMatchers.eq(Map.of()),
                        org.mockito.ArgumentMatchers.any()
                )
        ).thenReturn(Optional.empty());

        // when
        worker.process(claim);

        // then
        verifyNoInteractions(
                storage,
                assembler
        );
    }
}
