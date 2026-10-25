package com.knot.backend.recording.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;

import com.knot.backend.recording.application.dto.result.AiTranscript;
import com.knot.backend.recording.application.dto.result.AiWindowEvidence;
import com.knot.backend.recording.domain.RecordingException;
import com.knot.backend.recording.domain.model.RecordingTranscriptionWindow;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class IncrementalTranscriptMergerTest {
    private static final Instant NOW = Instant.parse("2026-10-18T00:00:00Z");

    private final ObjectMapper mapper = new ObjectMapper();
    private final IncrementalTranscriptMerger merger = new IncrementalTranscriptMerger(mapper);

    @Test
    @DisplayName("결과 도착 순서와 관계없이 core 순서로 화자와 발화를 결정한다")
    void merge_success_independentOfArrivalOrder() {
        // given
        RecordingTranscriptionWindow first = window(
                0,
                0,
                100,
                0,
                200,
                List.of(
                        token(
                                10,
                                20,
                                "시작",
                                "a"
                        )
                ),
                List.of(
                        speaker(
                                "a",
                                0
                        )
                )
        );
        RecordingTranscriptionWindow second = window(
                1,
                100,
                200,
                0,
                200,
                List.of(
                        token(
                                110,
                                120,
                                "계속",
                                "different-local"
                        )
                ),
                List.of(
                        speaker(
                                "different-local",
                                0
                        )
                )
        );

        // when
        AiTranscript result = merger.merge(
                List.of(
                        second,
                        first
                ),
                200,
                .65
        );

        // then
        assertThat(result.utterances()).hasSize(1);
        assertThat(
                result.utterances()
                        .getFirst()
                        .text()
        ).isEqualTo("시작 계속");
        assertThat(
                result.utterances()
                        .getFirst()
                        .speakerLabel()
        ).isEqualTo("SPEAKER_0");
    }

    @Test
    @DisplayName("겹친 문맥의 경계 단어는 단어 중심이 소유한 core에서 한 번만 보존한다")
    void merge_success_boundaryTokenExactlyOnce() {
        // given
        List<AiWindowEvidence.Token> tokens = List.of(
                token(
                        70,
                        90,
                        "앞",
                        "a"
                ),
                token(
                        90,
                        110,
                        "경계",
                        "a"
                ),
                token(
                        110,
                        130,
                        "뒤",
                        "a"
                )
        );
        List<AiWindowEvidence.Speaker> speakers = List.of(
                speaker(
                        "a",
                        0
                )
        );
        RecordingTranscriptionWindow first = window(
                0,
                0,
                100,
                0,
                200,
                tokens,
                speakers
        );
        RecordingTranscriptionWindow second = window(
                1,
                100,
                200,
                0,
                200,
                tokens,
                speakers
        );

        // when
        AiTranscript result = merger.merge(
                List.of(
                        first,
                        second
                ),
                200,
                .65
        );

        // then
        assertThat(
                result.utterances()
                        .getFirst()
                        .text()
        ).isEqualTo("앞 경계 뒤");
        assertThat(
                result.utterances()
                        .getFirst()
                        .startSec()
        ).isEqualTo(70 / 16000.0);
        assertThat(
                result.utterances()
                        .getFirst()
                        .endSec()
        ).isEqualTo(130 / 16000.0);
    }

    @Test
    @DisplayName("2분 구간은 직전 10초에서 겹친 단어를 정렬해 한 번만 이어 붙인다")
    void merge_success_seamAlignedOverlapOnce() {
        // given
        long second = 16_000;
        List<AiWindowEvidence.Speaker> speakers = List.of(
                speaker(
                        "a",
                        0
                )
        );
        RecordingTranscriptionWindow first = window(
                0,
                0,
                120 * second,
                0,
                120 * second,
                List.of(
                        token(
                                second,
                                2 * second,
                                "안녕하세요",
                                "a"
                        ),
                        token(
                                115 * second,
                                116 * second,
                                "회의를",
                                "a"
                        ),
                        token(
                                116 * second,
                                117 * second,
                                "시작합니다",
                                "a"
                        )
                ),
                speakers
        );
        RecordingTranscriptionWindow second2 = window(
                1,
                120 * second,
                240 * second,
                110 * second,
                240 * second,
                List.of(
                        token(
                                5 * second,
                                6 * second,
                                "회의를",
                                "a"
                        ),
                        token(
                                6 * second,
                                7 * second,
                                "시작합니다",
                                "a"
                        ),
                        token(
                                20 * second,
                                21 * second,
                                "첫",
                                "a"
                        )
                ),
                speakers
        );

        // when
        AiTranscript result = merger.merge(
                List.of(
                        first,
                        second2
                ),
                240 * second,
                .65,
                true
        );

        // then
        assertThat(result.utterances()).extracting(AiTranscript.Utterance::text)
                .containsExactly("안녕하세요 회의를 시작합니다 첫");
    }

    @Test
    @DisplayName("같은 window의 서로 다른 화자는 동일한 임베딩이어도 합치지 않는다")
    void merge_success_withinWindowCannotLink() {
        // given
        RecordingTranscriptionWindow first = window(
                0,
                0,
                100,
                0,
                200,
                List.of(
                        token(
                                10,
                                20,
                                "기존",
                                "a"
                        )
                ),
                List.of(
                        speaker(
                                "a",
                                0
                        )
                )
        );
        RecordingTranscriptionWindow second = window(
                1,
                100,
                200,
                0,
                200,
                List.of(
                        token(
                                110,
                                120,
                                "네",
                                "a"
                        ),
                        token(
                                130,
                                140,
                                "응",
                                "b"
                        )
                ),
                List.of(
                        speaker(
                                "a",
                                0
                        ),
                        speaker(
                                "b",
                                0
                        )
                )
        );

        // when
        AiTranscript result = merger.merge(
                List.of(
                        first,
                        second
                ),
                200,
                .65
        );

        // then
        assertThat(result.utterances()).extracting(AiTranscript.Utterance::speakerLabel)
                .containsExactly(
                        "SPEAKER_0",
                        "SPEAKER_1"
                );
        assertThat(result.utterances()).extracting(AiTranscript.Utterance::text)
                .containsExactly(
                        "기존 네",
                        "응"
                );
    }

    @Test
    @DisplayName("임베딩 없는 짧은 화자의 텍스트를 보존하고 window별 고유 화자로 남긴다")
    void merge_success_missingEmbeddingPreservesShortSpeaker() {
        // given
        List<AiWindowEvidence.Speaker> speakers = List.of(
                new AiWindowEvidence.Speaker(
                        "short",
                        List.of(),
                        1
                )
        );
        RecordingTranscriptionWindow first = window(
                0,
                0,
                100,
                0,
                200,
                List.of(
                        token(
                                10,
                                11,
                                "네",
                                "short"
                        )
                ),
                speakers
        );
        RecordingTranscriptionWindow second = window(
                1,
                100,
                200,
                0,
                200,
                List.of(
                        token(
                                110,
                                111,
                                "네",
                                "short"
                        )
                ),
                speakers
        );

        // when
        AiTranscript result = merger.merge(
                List.of(
                        first,
                        second
                ),
                200,
                .65
        );

        // then
        assertThat(result.utterances()).extracting(AiTranscript.Utterance::text)
                .containsExactly(
                        "네",
                        "네"
                );
        assertThat(result.utterances()).extracting(AiTranscript.Utterance::speakerLabel)
                .containsExactly(
                        "UNKNOWN_0_short",
                        "UNKNOWN_1_short"
                );
    }

    @Test
    @DisplayName("최종 raw JSON은 통합 발화와 같은 텍스트와 밀리초 범위를 담는다")
    void merge_success_rawTextMatchesUtterances() {
        // given
        RecordingTranscriptionWindow first = window(
                0,
                0,
                16000,
                0,
                16000,
                List.of(
                        token(
                                0,
                                8000,
                                "안녕",
                                "a"
                        ),
                        token(
                                8000,
                                16000,
                                "반가워",
                                "b"
                        )
                ),
                List.of(
                        speaker(
                                "a",
                                0
                        ),
                        speaker(
                                "b",
                                1
                        )
                )
        );

        // when
        AiTranscript result = merger.merge(
                List.of(first),
                16000,
                .65
        );

        // then
        JsonNode transcription = mapper.readTree(result.rawWhisperJson())
                .path("transcription");
        assertThat(transcription.size()).isEqualTo(
                result.utterances()
                        .size()
        );
        assertThat(
                transcription.get(0)
                        .path("text")
                        .asString()
        ).isEqualTo("안녕");
        assertThat(
                transcription.get(1)
                        .path("text")
                        .asString()
        ).isEqualTo("반가워");
        assertThat(
                transcription.get(0)
                        .path("offsets")
                        .path("from")
                        .asLong()
        ).isZero();
        assertThat(
                transcription.get(1)
                        .path("offsets")
                        .path("to")
                        .asLong()
        ).isEqualTo(1000);
        assertThat(result.toUtterances()).hasSize(2);
    }

    @Test
    @DisplayName("전 구간이 무음이면 유효한 빈 전사 결과를 반환한다")
    void merge_success_silentRecording() {
        // given
        RecordingTranscriptionWindow first = window(
                0,
                0,
                100,
                0,
                100,
                List.of(),
                List.of()
        );

        // when
        AiTranscript result = merger.merge(
                List.of(first),
                100,
                .65
        );

        // then
        assertThat(result.utterances()).isEmpty();
        assertThat(
                mapper.readTree(result.rawWhisperJson())
                        .path("transcription")
                        .isEmpty()
        ).isTrue();
    }

    @Test
    @DisplayName("core coverage에 빈 구간이 있으면 최종 전사를 만들지 않는다")
    void merge_failure_coverageHole() {
        // given
        RecordingTranscriptionWindow first = window(
                0,
                0,
                90,
                0,
                200,
                List.of(),
                List.of()
        );
        RecordingTranscriptionWindow second = window(
                1,
                100,
                200,
                0,
                200,
                List.of(),
                List.of()
        );

        // when
        Throwable failure = catchThrowable(
                () -> merger.merge(
                        List.of(
                                first,
                                second
                        ),
                        200,
                        .65
                )
        );

        // then
        assertThat(failure).isInstanceOf(RecordingException.class);
    }

    @Test
    @DisplayName("같은 core를 중복으로 전달하면 거절한다")
    void merge_failure_duplicateWindow() {
        // given
        RecordingTranscriptionWindow first = window(
                0,
                0,
                100,
                0,
                100,
                List.of(),
                List.of()
        );

        // when
        Throwable failure = catchThrowable(
                () -> merger.merge(
                        List.of(
                                first,
                                first
                        ),
                        100,
                        .65
                )
        );

        // then
        assertThat(failure).isInstanceOf(RecordingException.class);
    }

    @Test
    @DisplayName("마지막 core가 전체 녹음 끝까지 덮지 않으면 거절한다")
    void merge_failure_missingTail() {
        // given
        RecordingTranscriptionWindow first = window(
                0,
                0,
                100,
                0,
                100,
                List.of(),
                List.of()
        );

        // when
        Throwable failure = catchThrowable(
                () -> merger.merge(
                        List.of(first),
                        200,
                        .65
                )
        );

        // then
        assertThat(failure).isInstanceOf(RecordingException.class);
    }

    @Test
    @DisplayName("결과에 묶인 입력 manifest hash가 다르면 거절한다")
    void merge_failure_wrongManifestIdentity() {
        // given
        RecordingTranscriptionWindow first = queued(
                0,
                0,
                100,
                0,
                100
        );
        complete(
                first,
                new AiWindowEvidence(
                        1,
                        "test-v1",
                        "f".repeat(64),
                        0,
                        100,
                        "model-v1",
                        List.of(),
                        List.of()
                )
        );

        // when
        Throwable failure = catchThrowable(
                () -> merger.merge(
                        List.of(first),
                        100,
                        .65
                )
        );

        // then
        assertThat(failure).isInstanceOf(RecordingException.class);
    }

    @Test
    @DisplayName("결과 입력 범위가 저장된 window 범위와 다르면 거절한다")
    void merge_failure_wrongFrameIdentity() {
        // given
        RecordingTranscriptionWindow first = queued(
                0,
                0,
                100,
                0,
                100
        );
        complete(
                first,
                new AiWindowEvidence(
                        1,
                        "test-v1",
                        first.getManifestSha256(),
                        0,
                        99,
                        "model-v1",
                        List.of(),
                        List.of()
                )
        );

        // when
        Throwable failure = catchThrowable(
                () -> merger.merge(
                        List.of(first),
                        100,
                        .65
                )
        );

        // then
        assertThat(failure).isInstanceOf(RecordingException.class);
    }

    @Test
    @DisplayName("서로 다른 모델 버전의 결과를 섞지 않는다")
    void merge_failure_mixedModelVersions() {
        // given
        RecordingTranscriptionWindow first = window(
                0,
                0,
                100,
                0,
                200,
                List.of(),
                List.of()
        );
        RecordingTranscriptionWindow second = queued(
                1,
                100,
                200,
                0,
                200
        );
        complete(
                second,
                new AiWindowEvidence(
                        1,
                        "test-v1",
                        second.getManifestSha256(),
                        0,
                        200,
                        "model-v2",
                        List.of(),
                        List.of()
                )
        );

        // when
        Throwable failure = catchThrowable(
                () -> merger.merge(
                        List.of(
                                first,
                                second
                        ),
                        200,
                        .65
                )
        );

        // then
        assertThat(failure).isInstanceOf(RecordingException.class);
    }

    @Test
    @DisplayName("성공하지 않은 작업은 최종 전사에 채택하지 않는다")
    void merge_failure_pendingWindow() {
        // given
        RecordingTranscriptionWindow first = queued(
                0,
                0,
                100,
                0,
                100
        );

        // when
        Throwable failure = catchThrowable(
                () -> merger.merge(
                        List.of(first),
                        100,
                        .65
                )
        );

        // then
        assertThat(failure).isInstanceOf(RecordingException.class);
    }

    @Test
    @DisplayName("같은 문장을 연속해서 말해도 문자열 중복 제거로 지우지 않는다")
    void merge_success_repeatedWordsPreserved() {
        // given
        RecordingTranscriptionWindow first = window(
                0,
                0,
                100,
                0,
                100,
                List.of(
                        token(
                                0,
                                10,
                                "네",
                                "a"
                        ),
                        token(
                                20,
                                30,
                                "네",
                                "a"
                        )
                ),
                List.of(
                        speaker(
                                "a",
                                0
                        )
                )
        );

        // when
        AiTranscript result = merger.merge(
                List.of(first),
                100,
                .65
        );

        // then
        assertThat(
                result.utterances()
                        .getFirst()
                        .text()
        ).isEqualTo("네 네");
    }

    @Test
    @DisplayName("저장된 결과 hash와 실제 결과 JSON이 다르면 거절한다")
    void merge_failure_resultHashMismatch() {
        // given
        RecordingTranscriptionWindow first = spy(
                window(
                        0,
                        0,
                        100,
                        0,
                        100,
                        List.of(),
                        List.of()
                )
        );
        doReturn("f".repeat(64)).when(first)
                .getResultSha256();

        // when
        Throwable failure = catchThrowable(
                () -> merger.merge(
                        List.of(first),
                        100,
                        .65
                )
        );

        // then
        assertThat(failure).isInstanceOf(RecordingException.class);
    }

    @Test
    @DisplayName("저장 결과의 스키마 버전이 지원 버전이 아니면 거절한다")
    void merge_failure_storedSchemaMismatch() {
        // given
        RecordingTranscriptionWindow first = spy(
                window(
                        0,
                        0,
                        100,
                        0,
                        100,
                        List.of(),
                        List.of()
                )
        );
        doReturn(2).when(first)
                .getResultSchemaVersion();

        // when
        Throwable failure = catchThrowable(
                () -> merger.merge(
                        List.of(first),
                        100,
                        .65
                )
        );

        // then
        assertThat(failure).isInstanceOf(RecordingException.class);
    }

    @Test
    @DisplayName("결과에 고정된 pipeline 버전이 window와 다르면 거절한다")
    void merge_failure_pipelineMismatch() {
        // given
        RecordingTranscriptionWindow first = queued(
                0,
                0,
                100,
                0,
                100
        );
        complete(
                first,
                new AiWindowEvidence(
                        1,
                        "different",
                        first.getManifestSha256(),
                        0,
                        100,
                        "model-v1",
                        List.of(),
                        List.of()
                )
        );

        // when
        Throwable failure = catchThrowable(
                () -> merger.merge(
                        List.of(first),
                        100,
                        .65
                )
        );

        // then
        assertThat(failure).isInstanceOf(RecordingException.class);
    }

    @Test
    @DisplayName("미확정 화자는 알려진 화자와 연결되거나 화자 번호를 소비하지 않는다")
    void merge_success_unknownDoesNotAffectKnownSpeakers() {
        // given
        RecordingTranscriptionWindow first = window(
                0,
                0,
                100,
                0,
                200,
                List.of(
                        token(
                                0,
                                10,
                                "누군가",
                                "unknown"
                        ),
                        token(
                                20,
                                30,
                                "알려진",
                                "known"
                        )
                ),
                List.of(
                        new AiWindowEvidence.Speaker(
                                "unknown",
                                List.of(),
                                0
                        ),
                        speaker(
                                "known",
                                0
                        )
                )
        );
        RecordingTranscriptionWindow second = window(
                1,
                100,
                200,
                0,
                200,
                List.of(
                        token(
                                100,
                                110,
                                "이어말해",
                                "same-voice"
                        ),
                        token(
                                120,
                                130,
                                "누군가",
                                "unknown"
                        )
                ),
                List.of(
                        speaker(
                                "same-voice",
                                0
                        ),
                        new AiWindowEvidence.Speaker(
                                "unknown",
                                List.of(),
                                0
                        )
                )
        );

        // when
        AiTranscript result = merger.merge(
                List.of(
                        second,
                        first
                ),
                200,
                .65
        );

        // then
        assertThat(result.utterances()).extracting(AiTranscript.Utterance::speakerLabel)
                .containsExactly(
                        "UNKNOWN_0_unknown",
                        "SPEAKER_0",
                        "UNKNOWN_1_unknown"
                );
        assertThat(result.utterances()).extracting(AiTranscript.Utterance::text)
                .containsExactly(
                        "누군가",
                        "알려진 이어말해",
                        "누군가"
                );
    }

    private RecordingTranscriptionWindow window(
            int index,
            long coreStart,
            long coreEnd,
            long inputStart,
            long inputEnd,
            List<AiWindowEvidence.Token> tokens,
            List<AiWindowEvidence.Speaker> speakers
    ) {
        RecordingTranscriptionWindow window = queued(
                index,
                coreStart,
                coreEnd,
                inputStart,
                inputEnd
        );
        complete(
                window,
                new AiWindowEvidence(
                        1,
                        "test-v1",
                        window.getManifestSha256(),
                        inputStart,
                        inputEnd,
                        "model-v1",
                        tokens,
                        speakers
                )
        );
        return window;
    }

    private RecordingTranscriptionWindow queued(
            int index,
            long coreStart,
            long coreEnd,
            long inputStart,
            long inputEnd
    ) {
        return RecordingTranscriptionWindow.queued(
                1,
                "test-v1",
                index,
                coreStart,
                coreEnd,
                inputStart,
                inputEnd,
                "{\"parts\":[]}",
                UUID.randomUUID(),
                NOW
        );
    }

    private void complete(
            RecordingTranscriptionWindow window,
            AiWindowEvidence evidence
    ) {
        UUID owner = UUID.randomUUID();
        window.claim(
                owner,
                NOW,
                Duration.ofMinutes(1),
                Duration.ofMinutes(2)
        );
        window.succeed(
                window.getAttemptId(),
                owner,
                window.getClaimEpoch(),
                NOW,
                1,
                mapper.writeValueAsString(evidence)
        );
    }

    private AiWindowEvidence.Token token(
            long start,
            long end,
            String text,
            String speakerId
    ) {
        return new AiWindowEvidence.Token(
                start,
                end,
                text,
                speakerId
        );
    }

    private AiWindowEvidence.Speaker speaker(
            String speakerId,
            int axis
    ) {
        List<Double> embedding = new ArrayList<>(
                Collections.nCopies(
                        512,
                        0.0
                )
        );
        embedding.set(
                axis,
                1.0
        );
        return new AiWindowEvidence.Speaker(
                speakerId,
                embedding,
                10
        );
    }
}
