package com.knot.backend.recording.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.knot.backend.recording.application.dto.command.RecordingAudioChunkCommand;
import com.knot.backend.recording.application.dto.command.RecordingControlCommand;
import com.knot.backend.recording.application.dto.result.PresignedAudioChunk;
import com.knot.backend.recording.application.dto.result.RecordingAudioChunkCompletionResult;
import com.knot.backend.recording.application.dto.result.RecordingAudioChunkConfirmation;
import com.knot.backend.recording.application.dto.result.RecordingAudioChunkReservation;
import com.knot.backend.recording.application.dto.result.RecordingAudioChunkReservationResult;
import com.knot.backend.recording.domain.RecordingErrorCode;
import com.knot.backend.recording.domain.RecordingException;
import com.knot.backend.recording.domain.model.AudioChunkDescriptor;
import com.knot.backend.recording.domain.model.RecordingAudioChunkStatus;
import com.knot.backend.recording.domain.model.StoredAudioChunk;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.InOrder;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class RecordingAudioChunkServiceTest {
    private static final Instant NOW = Instant.parse("2026-10-17T00:00:10Z");
    private static final String HASH = Base64.getEncoder()
            .encodeToString(new byte[32]);
    private static final RecordingControlCommand CONTROL = createControl();
    private static final RecordingAudioChunkCommand COMMAND = createCommand();

    @Mock
    private RecordingAudioChunkTransaction transaction;
    @Mock
    private RecordingAudioChunkStorage storage;
    private RecordingAudioChunkService service;

    @BeforeEach
    void setUp() {
        service = new RecordingAudioChunkService(
                transaction,
                storage,
                Clock.fixed(
                        NOW,
                        ZoneOffset.UTC
                ),
                org.mockito.Mockito.mock(com.knot.backend.recording.application.RecordingAudioFinalizationQuery.class),
                Duration.ofMinutes(15)
        );
    }

    @Test
    @DisplayName("예약을 먼저 저장하고 서명한 뒤 권한을 재검증한 결과를 반환한다")
    void reserve_success_postSignValidation() {
        // given
        RecordingAudioChunkReservation reserved = new RecordingAudioChunkReservation(
                true,
                1,
                "chunks/1",
                160000,
                HASH,
                NOW.plusSeconds(900),
                null
        );
        PresignedAudioChunk signed = new PresignedAudioChunk(
                "https://storage.invalid/upload",
                NOW.plusSeconds(899),
                Map.of()
        );
        RecordingAudioChunkReservationResult expected = new RecordingAudioChunkReservationResult(
                true,
                1,
                RecordingAudioChunkStatus.RESERVED,
                signed.uploadUrl(),
                signed.expiresAt(),
                signed.requiredHeaders()
        );
        when(
                transaction.reserve(
                        1,
                        2,
                        3,
                        COMMAND,
                        NOW.plusSeconds(900)
                )
        ).thenReturn(reserved);
        when(
                storage.presignUpload(
                        "chunks/1",
                        160000,
                        HASH,
                        reserved.expiresAt()
                )
        ).thenReturn(signed);
        when(
                transaction.confirmReservation(
                        1,
                        2,
                        3,
                        COMMAND,
                        reserved,
                        signed
                )
        ).thenReturn(expected);

        // when
        RecordingAudioChunkReservationResult result = service.reserve(
                1,
                2,
                3,
                COMMAND
        );

        // then
        assertThat(result).isEqualTo(expected);
        InOrder order = inOrder(
                transaction,
                storage
        );
        order.verify(transaction)
                .reserve(
                        1,
                        2,
                        3,
                        COMMAND,
                        NOW.plusSeconds(900)
                );
        order.verify(storage)
                .presignUpload(
                        "chunks/1",
                        160000,
                        HASH,
                        reserved.expiresAt()
                );
        order.verify(transaction)
                .confirmReservation(
                        1,
                        2,
                        3,
                        COMMAND,
                        reserved,
                        signed
                );
    }

    @Test
    @DisplayName("이미 VERIFIED인 예약은 새 URL을 발급하지 않는다")
    void reserve_success_verifiedReservation() {
        // given
        RecordingAudioChunkReservationResult expected = new RecordingAudioChunkReservationResult(
                false,
                1,
                RecordingAudioChunkStatus.VERIFIED,
                null,
                null,
                Map.of()
        );
        RecordingAudioChunkReservation reserved = new RecordingAudioChunkReservation(
                false,
                1,
                "chunks/1",
                160000,
                HASH,
                NOW.plusSeconds(900),
                expected
        );
        when(
                transaction.reserve(
                        1,
                        2,
                        3,
                        COMMAND,
                        NOW.plusSeconds(900)
                )
        ).thenReturn(reserved);

        // when
        RecordingAudioChunkReservationResult result = service.reserve(
                1,
                2,
                3,
                COMMAND
        );

        // then
        assertThat(result).isEqualTo(expected);
        verifyNoInteractions(storage);
    }

    @Test
    @DisplayName("complete는 준비와 최종 트랜잭션 사이에 저장소 HEAD를 수행한다")
    void complete_success_headBetweenTransactions() {
        // given
        RecordingAudioChunkConfirmation preparation = new RecordingAudioChunkConfirmation(
                "chunks/1",
                null
        );
        StoredAudioChunk stored = new StoredAudioChunk(
                true,
                160000,
                "application/octet-stream",
                HASH,
                "version-1"
        );
        RecordingAudioChunkCompletionResult expected = new RecordingAudioChunkCompletionResult(
                1,
                RecordingAudioChunkStatus.VERIFIED,
                1,
                80000,
                5000,
                NOW
        );
        when(
                transaction.prepareConfirmation(
                        1,
                        2,
                        3,
                        1,
                        CONTROL
                )
        ).thenReturn(preparation);
        when(storage.findStoredObject("chunks/1")).thenReturn(stored);
        when(
                transaction.confirmStored(
                        1,
                        2,
                        3,
                        1,
                        CONTROL,
                        stored
                )
        ).thenReturn(expected);

        // when
        RecordingAudioChunkCompletionResult result = service.complete(
                1,
                2,
                3,
                1,
                CONTROL
        );

        // then
        assertThat(result).isEqualTo(expected);
        InOrder order = inOrder(
                transaction,
                storage
        );
        order.verify(transaction)
                .prepareConfirmation(
                        1,
                        2,
                        3,
                        1,
                        CONTROL
                );
        order.verify(storage)
                .findStoredObject("chunks/1");
        order.verify(transaction)
                .confirmStored(
                        1,
                        2,
                        3,
                        1,
                        CONTROL,
                        stored
                );
    }

    @Test
    @DisplayName("complete 응답 유실 재시도는 기존 ACK를 반환하며 HEAD를 반복하지 않는다")
    void complete_success_verifiedRetry() {
        // given
        RecordingAudioChunkCompletionResult expected = new RecordingAudioChunkCompletionResult(
                1,
                RecordingAudioChunkStatus.VERIFIED,
                1,
                80000,
                5000,
                NOW
        );
        when(
                transaction.prepareConfirmation(
                        1,
                        2,
                        3,
                        1,
                        CONTROL
                )
        ).thenReturn(
                new RecordingAudioChunkConfirmation(
                        null,
                        expected
                )
        );

        // when
        RecordingAudioChunkCompletionResult result = service.complete(
                1,
                2,
                3,
                1,
                CONTROL
        );

        // then
        assertThat(result).isEqualTo(expected);
        verifyNoInteractions(storage);
    }

    @Test
    @DisplayName("HEAD 장애는 성공이나 미저장으로 바꾸지 않고 호출자에게 전달한다")
    void complete_failure_unknownStorageResult() {
        // given
        when(
                transaction.prepareConfirmation(
                        1,
                        2,
                        3,
                        1,
                        CONTROL
                )
        ).thenReturn(
                new RecordingAudioChunkConfirmation(
                        "chunks/1",
                        null
                )
        );
        when(storage.findStoredObject("chunks/1"))
                .thenThrow(new RecordingException(RecordingErrorCode.AUDIO_STORAGE_UNAVAILABLE));

        // when
        Throwable thrown = catchThrowable(
                () -> service.complete(
                        1,
                        2,
                        3,
                        1,
                        CONTROL
                )
        );

        // then
        assertThat(thrown).isInstanceOf(RecordingException.class)
                .extracting("errorCode")
                .isEqualTo(RecordingErrorCode.AUDIO_STORAGE_UNAVAILABLE);
        org.mockito.Mockito.verify(
                transaction,
                org.mockito.Mockito.never()
        )
                .confirmStored(
                        anyLong(),
                        anyLong(),
                        anyLong(),
                        eq(1),
                        any(),
                        any()
                );
    }
    private static RecordingControlCommand createControl() {
        return new RecordingControlCommand(
                UUID.randomUUID(),
                "token"
        );
    }

    private static RecordingAudioChunkCommand createCommand() {
        return new RecordingAudioChunkCommand(
                CONTROL,
                new AudioChunkDescriptor(
                        1,
                        0,
                        80000,
                        160000,
                        HASH
                )
        );
    }

}
