package com.knot.backend.recording.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.knot.backend.recording.application.dto.command.RecordingAudioChunkCommand;
import com.knot.backend.recording.application.dto.command.RecordingControlCommand;
import com.knot.backend.recording.application.dto.result.PresignedAudioChunk;
import com.knot.backend.recording.application.dto.result.RecordingAudioChunkReservation;
import com.knot.backend.recording.application.dto.result.RecordingAudioChunkCompletionResult;
import com.knot.backend.recording.application.dto.result.RecordingAudioChunkConfirmation;
import com.knot.backend.recording.application.dto.result.RecordingAudioSaveResult;
import com.knot.backend.recording.domain.RecordingErrorCode;
import com.knot.backend.recording.domain.RecordingException;
import com.knot.backend.recording.domain.model.AudioChunkDescriptor;
import com.knot.backend.recording.domain.model.RecordingAudioChunk;
import com.knot.backend.recording.domain.model.RecordingAudioChunkStatus;
import com.knot.backend.recording.domain.model.RecordingAudioMode;
import com.knot.backend.recording.domain.model.RecordingAudioStream;
import com.knot.backend.recording.domain.model.RecordingSession;
import com.knot.backend.recording.domain.model.RecordingStatus;
import com.knot.backend.recording.domain.model.StoredAudioChunk;
import com.knot.backend.recording.domain.repository.RecordingAudioChunkRepository;
import com.knot.backend.recording.domain.repository.RecordingAudioStreamRepository;
import com.knot.backend.recording.domain.repository.RecordingSessionRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class RecordingAudioChunkTransactionTest {
    private static final Instant NOW = Instant.parse("2026-10-17T00:00:10Z");
    private static final Instant EXPIRES = NOW.plusSeconds(60);
    private static final String HASH = Base64.getEncoder()
            .encodeToString(new byte[32]);
    private static final RecordingControlCommand CONTROL = createControl();
    private static final RecordingAudioChunkCommand COMMAND = createCommand();

    @Mock
    private RecordingAccess access;
    @Mock
    private RecordingSessionRepository sessions;
    @Mock
    private RecordingAudioStreamRepository streams;
    @Mock
    private RecordingAudioChunkRepository chunks;
    private RecordingAudioChunkTransaction transaction;
    private RecordingSession session;
    private RecordingAudioStream stream;

    @BeforeEach
    void setUp() {
        transaction = new RecordingAudioChunkTransaction(
                access,
                sessions,
                streams,
                chunks,
                Clock.fixed(
                        NOW,
                        ZoneOffset.UTC
                )
        );
        session = RecordingSession.start(
                1L,
                2L,
                UUID.randomUUID(),
                CONTROL.tabId(),
                "0".repeat(64),
                NOW.minusSeconds(10),
                RecordingAudioMode.PCM16_CHUNKS_V1
        );
        stream = RecordingAudioStream.create(3);
    }

    @Test
    @DisplayName("새 조각 예약은 고정 키와 다음 위치를 저장하고 heartbeat를 변경하지 않는다")
    void reserve_success_newChunk() {
        // given
        controlled();
        when(
                chunks.findByRecordingIdAndSequence(
                        3,
                        1
                )
        ).thenReturn(Optional.empty());
        Instant lastSeen = session.getLastSeenAt();

        // when
        RecordingAudioChunkReservation result = transaction.reserve(
                1,
                2,
                3,
                COMMAND,
                EXPIRES
        );

        // then
        assertThat(result.created()).isTrue();
        assertThat(result.storageKey()).isEqualTo("recordings/3/chunks/1");
        assertThat(result.expiresAt()).isEqualTo(EXPIRES);
        assertThat(stream.getNextSequence()).isEqualTo(2);
        assertThat(session.getLastSeenAt()).isEqualTo(lastSeen);
        verify(chunks).save(any(RecordingAudioChunk.class));
        verify(streams).save(stream);
    }

    @Test
    @DisplayName("동일 조각 예약 재요청은 기존 키를 유지하고 stream을 전진하지 않는다")
    void reserve_success_identicalRetry() {
        // given
        controlled();
        stream.reserve(COMMAND.descriptor());
        RecordingAudioChunk chunk = reserved();
        when(
                chunks.findByRecordingIdAndSequence(
                        3,
                        1
                )
        ).thenReturn(Optional.of(chunk));

        // when
        RecordingAudioChunkReservation result = transaction.reserve(
                1,
                2,
                3,
                COMMAND,
                EXPIRES.plusSeconds(10)
        );

        // then
        assertThat(result.created()).isFalse();
        assertThat(result.storageKey()).isEqualTo(chunk.getStorageKey());
        assertThat(stream.getNextSequence()).isEqualTo(2);
        assertThat(chunk.getUploadUrlExpiresAt()).isEqualTo(EXPIRES.plusSeconds(10));
        verify(
                streams,
                never()
        ).save(any());
    }

    @Test
    @DisplayName("만료된 캡처의 신규 예약은 논리적 종료를 저장한 뒤 전용 예외로 거절한다")
    void reserve_failure_expiredCapture() {
        // given
        session = RecordingSession.start(
                1L,
                2L,
                UUID.randomUUID(),
                CONTROL.tabId(),
                "0".repeat(64),
                NOW.minusSeconds(40),
                RecordingAudioMode.PCM16_CHUNKS_V1
        );
        controlled();
        when(
                chunks.findByRecordingIdAndSequence(
                        3,
                        1
                )
        ).thenReturn(Optional.empty());

        // when
        Throwable thrown = catchThrowable(
                () -> transaction.reserve(
                        1,
                        2,
                        3,
                        COMMAND,
                        EXPIRES
                )
        );

        // then
        assertThat(thrown).isInstanceOf(RecordingAudioCaptureExpiredException.class);
        assertThat(session.getStatus()).isEqualTo(RecordingStatus.ENDED);
        assertThat(session.getEndedAt()).isEqualTo(NOW.minusSeconds(10));
        verify(sessions).save(session);
        verify(
                chunks,
                never()
        ).save(any());
        assertThat(stream.getNextSequence()).isEqualTo(1);
    }

    @Test
    @DisplayName("확정 범위가 고정되면 새 조각은 저장하지 않는다")
    void reserve_failure_frozen() {
        // given
        controlled();
        stream.freeze();
        when(
                chunks.findByRecordingIdAndSequence(
                        3,
                        1
                )
        ).thenReturn(Optional.empty());

        // when
        Throwable thrown = catchThrowable(
                () -> transaction.reserve(
                        1,
                        2,
                        3,
                        COMMAND,
                        EXPIRES
                )
        );

        // then
        assertError(
                thrown,
                RecordingErrorCode.AUDIO_SAVE_FROZEN
        );
        verify(
                chunks,
                never()
        ).save(any());
    }

    @Test
    @DisplayName("서명 중 녹음이 폐기되면 발급한 URL을 반환하지 않는다")
    void confirmReservation_failure_discardedWhileSigning() {
        // given
        controlled();
        when(
                chunks.findByRecordingIdAndSequence(
                        3,
                        1
                )
        ).thenReturn(Optional.empty());
        RecordingAudioChunkReservation reservation = transaction.reserve(
                1,
                2,
                3,
                COMMAND,
                EXPIRES
        );
        session.discard(NOW);
        PresignedAudioChunk signed = new PresignedAudioChunk(
                "https://storage.invalid/signed",
                EXPIRES,
                Map.of()
        );

        // when
        Throwable thrown = catchThrowable(
                () -> transaction.confirmReservation(
                        1,
                        2,
                        3,
                        COMMAND,
                        reservation,
                        signed
                )
        );

        // then
        assertError(
                thrown,
                RecordingErrorCode.RECORDING_ALREADY_DISCARDED
        );
    }

    @Test
    @DisplayName("HEAD 중 범위가 고정되면 새 VERIFIED ACK를 저장하지 않는다")
    void confirmStored_failure_frozenAfterHead() {
        // given
        controlled();
        RecordingAudioChunk chunk = reserved();
        when(
                chunks.findByRecordingIdAndSequence(
                        3,
                        1
                )
        ).thenReturn(Optional.of(chunk));
        transaction.prepareConfirmation(
                1,
                2,
                3,
                1,
                CONTROL
        );
        stream.freeze();

        // when
        Throwable thrown = catchThrowable(
                () -> transaction.confirmStored(
                        1,
                        2,
                        3,
                        1,
                        CONTROL,
                        stored()
                )
        );

        // then
        assertError(
                thrown,
                RecordingErrorCode.AUDIO_SAVE_FROZEN
        );
        assertThat(chunk.getStatus()).isEqualTo(RecordingAudioChunkStatus.RESERVED);
        verify(
                chunks,
                never()
        ).save(any());
    }

    @Test
    @DisplayName("저장 확인 뒤 VERIFIED를 저장하고 연속 저장량을 반환한다")
    void confirmStored_success() {
        // given
        controlled();
        RecordingAudioChunk chunk = reserved();
        when(
                chunks.findByRecordingIdAndSequence(
                        3,
                        1
                )
        ).thenReturn(Optional.of(chunk));
        when(chunks.findAllByRecordingIdOrderBySequence(3)).thenReturn(List.of(chunk));

        // when
        RecordingAudioChunkCompletionResult result = transaction.confirmStored(
                1,
                2,
                3,
                1,
                CONTROL,
                stored()
        );

        // then
        assertThat(result.verifiedThroughSequence()).isEqualTo(1);
        assertThat(result.verifiedThroughFrame()).isEqualTo(80000);
        assertThat(result.verifiedAudioMillis()).isEqualTo(5000);
        assertThat(result.confirmedAt()).isEqualTo(NOW);
        verify(chunks).save(chunk);
    }

    @Test
    @DisplayName("이미 VERIFIED인 조각은 종료와 freeze 뒤에도 기존 ACK를 반환한다")
    void prepareConfirmation_success_verifiedEndedFrozen() {
        // given
        controlled();
        RecordingAudioChunk chunk = reserved();
        chunk.verify(
                stored(),
                NOW
        );
        session.end(NOW);
        stream.freeze();
        when(
                chunks.findByRecordingIdAndSequence(
                        3,
                        1
                )
        ).thenReturn(Optional.of(chunk));
        when(chunks.findAllByRecordingIdOrderBySequence(3)).thenReturn(List.of(chunk));

        // when
        RecordingAudioChunkConfirmation result = transaction.prepareConfirmation(
                1,
                2,
                3,
                1,
                CONTROL
        );

        // then
        assertThat(result.storageKey()).isNull();
        assertThat(
                result.completed()
                        .status()
        ).isEqualTo(RecordingAudioChunkStatus.VERIFIED);
        assertThat(
                result.completed()
                        .confirmedAt()
        ).isEqualTo(NOW);
    }

    @Test
    @DisplayName("없는 예약의 complete는 미존재 오류를 반환한다")
    void prepareConfirmation_failure_missingReservation() {
        // given
        controlled();
        when(
                chunks.findByRecordingIdAndSequence(
                        3,
                        1
                )
        ).thenReturn(Optional.empty());

        // when
        Throwable thrown = catchThrowable(
                () -> transaction.prepareConfirmation(
                        1,
                        2,
                        3,
                        1,
                        CONTROL
                )
        );

        // then
        assertError(
                thrown,
                RecordingErrorCode.AUDIO_CHUNK_NOT_FOUND
        );
    }

    @Test
    @DisplayName("뒤 조각만 VERIFIED이면 연속 저장량은 증가하지 않고 구간을 별도로 표시한다")
    void findState_success_outOfOrderVerification() {
        // given
        RecordingAudioChunk first = reserved();
        RecordingAudioChunk second = RecordingAudioChunk.reserve(
                3,
                new AudioChunkDescriptor(
                        2,
                        80000,
                        80000,
                        160000,
                        HASH
                ),
                "chunks/2",
                EXPIRES
        );
        second.verify(
                stored(),
                NOW
        );
        when(sessions.findById(3L)).thenReturn(Optional.of(session));
        when(streams.findByRecordingId(3)).thenReturn(Optional.of(stream));
        when(chunks.findAllByRecordingIdOrderBySequence(3)).thenReturn(
                List.of(
                        first,
                        second
                )
        );
        Instant originalLastSeen = session.getLastSeenAt();

        // when
        RecordingAudioSaveResult result = transaction.findState(
                1,
                2,
                3
        );

        // then
        assertThat(result.verifiedThroughFrame()).isZero();
        assertThat(result.verifiedRanges()).hasSize(1);
        assertThat(
                result.verifiedRanges()
                        .getFirst()
                        .start()
        ).isEqualTo(80000);
        assertThat(
                result.verifiedRanges()
                        .getFirst()
                        .endExclusive()
        ).isEqualTo(160000);
        assertThat(
                result.missingRanges()
                        .getFirst()
                        .start()
        ).isZero();
        assertThat(result.audioSaveStatus()).isEqualTo("CAPTURING");
        assertThat(result.jobId()).isNull();
        assertThat(result.quality()).isNull();
        assertThat(session.getLastSeenAt()).isEqualTo(originalLastSeen);
        verify(
                sessions,
                never()
        ).save(any());
        verify(access).validateMember(
                1,
                2
        );
    }

    private void controlled() {
        when(
                access.loadControlledForUpdate(
                        1,
                        2,
                        3,
                        CONTROL
                )
        ).thenReturn(session);
        when(streams.findByRecordingIdForUpdate(3)).thenReturn(Optional.of(stream));
    }

    private RecordingAudioChunk reserved() {
        return RecordingAudioChunk.reserve(
                3,
                COMMAND.descriptor(),
                "recordings/3/chunks/1",
                EXPIRES
        );
    }

    private StoredAudioChunk stored() {
        return new StoredAudioChunk(
                true,
                160000,
                "application/octet-stream",
                HASH,
                "version-1"
        );
    }

    private void assertError(
            Throwable thrown,
            RecordingErrorCode code
    ) {
        assertThat(thrown).isInstanceOf(RecordingException.class)
                .extracting("errorCode")
                .isEqualTo(code);
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
