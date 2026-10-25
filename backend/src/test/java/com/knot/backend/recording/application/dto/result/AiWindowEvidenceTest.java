package com.knot.backend.recording.application.dto.result;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.knot.backend.recording.domain.RecordingException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.ObjectMapper;

class AiWindowEvidenceTest {
    private static final String HASH = "a".repeat(64);

    @Test
    @DisplayName("유효한 단어 근거를 JSON 왕복하고 목록을 불변으로 보관한다")
    void create_success_roundTripAndImmutableLists() {
        // given
        List<AiWindowEvidence.Token> tokens = new ArrayList<>(
                List.of(
                        token(
                                0,
                                100,
                                "a"
                        )
                )
        );
        AiWindowEvidence evidence = evidence(
                tokens,
                List.of(
                        speaker(
                                "a",
                                List.of(),
                                100
                        )
                )
        );
        ObjectMapper mapper = new ObjectMapper();

        // when
        AiWindowEvidence restored = mapper.readValue(
                mapper.writeValueAsString(evidence),
                AiWindowEvidence.class
        );

        // then
        tokens.clear();
        assertThat(restored).isEqualTo(evidence);
        assertThat(evidence.tokens()).hasSize(1);
        assertThat(
                catchThrowable(
                        () -> evidence.tokens()
                                .clear()
                )
        ).isInstanceOf(UnsupportedOperationException.class);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 2})
    @DisplayName("지원하지 않는 결과 스키마를 거절한다")
    void create_failure_unsupportedSchema(int version) {
        // given
        List<AiWindowEvidence.Token> tokens = List.of();

        // when
        Throwable failure = catchThrowable(
                () -> new AiWindowEvidence(
                        version,
                        "v1",
                        HASH,
                        0,
                        100,
                        "model",
                        tokens,
                        List.of()
                )
        );

        // then
        assertThat(failure).isInstanceOf(RecordingException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "ABCDEF", "a", "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"})
    @DisplayName("소문자 SHA256 형식이 아닌 입력 식별자를 거절한다")
    void create_failure_invalidManifestHash(String hash) {
        // given
        List<AiWindowEvidence.Token> tokens = List.of();

        // when
        Throwable failure = catchThrowable(
                () -> new AiWindowEvidence(
                        1,
                        "v1",
                        hash,
                        0,
                        100,
                        "model",
                        tokens,
                        List.of()
                )
        );

        // then
        assertThat(failure).isInstanceOf(RecordingException.class);
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1, 2080001})
    @DisplayName("입력 범위는 양수이며 최대 130초를 넘지 않는다")
    void create_failure_invalidInputSpan(long end) {
        // given
        List<AiWindowEvidence.Token> tokens = List.of();

        // when
        Throwable failure = catchThrowable(
                () -> new AiWindowEvidence(
                        1,
                        "v1",
                        HASH,
                        0,
                        end,
                        "model",
                        tokens,
                        List.of()
                )
        );

        // then
        assertThat(failure).isInstanceOf(RecordingException.class);
    }

    @Test
    @DisplayName("단어가 선언되지 않은 화자를 참조하면 거절한다")
    void create_failure_unknownSpeakerReference() {
        // given
        List<AiWindowEvidence.Token> tokens = List.of(
                token(
                        0,
                        1,
                        "missing"
                )
        );

        // when
        Throwable failure = catchThrowable(
                () -> evidence(
                        tokens,
                        List.of(
                                speaker(
                                        "a",
                                        List.of(),
                                        1
                                )
                        )
                )
        );

        // then
        assertThat(failure).isInstanceOf(RecordingException.class);
    }

    @Test
    @DisplayName("단어 시작 시각이 역순이면 거절한다")
    void create_failure_nonMonotonicTokenStarts() {
        // given
        List<AiWindowEvidence.Token> tokens = List.of(
                token(
                        20,
                        30,
                        "a"
                ),
                token(
                        10,
                        15,
                        "a"
                )
        );

        // when
        Throwable failure = catchThrowable(
                () -> evidence(
                        tokens,
                        List.of(
                                speaker(
                                        "a",
                                        List.of(),
                                        1
                                )
                        )
                )
        );

        // then
        assertThat(failure).isInstanceOf(RecordingException.class);
    }

    @Test
    @DisplayName("동시 발화의 겹친 시간 범위는 그대로 보존한다")
    void create_success_overlappingSpeech() {
        // given
        List<AiWindowEvidence.Token> tokens = List.of(
                token(
                        0,
                        100,
                        "a"
                ),
                token(
                        10,
                        20,
                        "b"
                )
        );

        // when
        AiWindowEvidence result = evidence(
                tokens,
                List.of(
                        speaker(
                                "a",
                                List.of(),
                                100
                        ),
                        speaker(
                                "b",
                                List.of(),
                                10
                        )
                )
        );

        // then
        assertThat(result.tokens()).hasSize(2);
    }

    @Test
    @DisplayName("입력 범위 밖 단어 시각은 거절한다")
    void create_failure_tokenBeyondInput() {
        // given
        List<AiWindowEvidence.Token> tokens = List.of(
                token(
                        0,
                        101,
                        "a"
                )
        );

        // when
        Throwable failure = catchThrowable(
                () -> evidence(
                        tokens,
                        List.of(
                                speaker(
                                        "a",
                                        List.of(),
                                        1
                                )
                        )
                )
        );

        // then
        assertThat(failure).isInstanceOf(RecordingException.class);
    }

    @Test
    @DisplayName("같은 local 화자 ID를 중복 선언하면 거절한다")
    void create_failure_duplicateSpeaker() {
        // given
        AiWindowEvidence.Speaker speaker = speaker(
                "a",
                List.of(),
                1
        );

        // when
        Throwable failure = catchThrowable(
                () -> evidence(
                        List.of(),
                        List.of(
                                speaker,
                                speaker
                        )
                )
        );

        // then
        assertThat(failure).isInstanceOf(RecordingException.class);
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 511, 513})
    @DisplayName("누락 임베딩 외에는 정확히 512차원만 허용한다")
    void speaker_failure_invalidDimension(int dimensions) {
        // given
        List<Double> embedding = Collections.nCopies(
                dimensions,
                1.0
        );

        // when
        Throwable failure = catchThrowable(
                () -> speaker(
                        "a",
                        embedding,
                        1
                )
        );

        // then
        assertThat(failure).isInstanceOf(RecordingException.class);
    }

    @ParameterizedTest
    @ValueSource(doubles = {Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY})
    @DisplayName("임베딩의 비유한 성분은 거절한다")
    void speaker_failure_nonFiniteEmbedding(double component) {
        // given
        List<Double> embedding = new ArrayList<>(
                Collections.nCopies(
                        512,
                        1.0
                )
        );
        embedding.set(
                10,
                component
        );

        // when
        Throwable failure = catchThrowable(
                () -> speaker(
                        "a",
                        embedding,
                        1
                )
        );

        // then
        assertThat(failure).isInstanceOf(RecordingException.class);
    }

    @Test
    @DisplayName("영벡터 임베딩은 유효한 화자 근거로 취급하지 않는다")
    void speaker_failure_zeroVector() {
        // given
        List<Double> embedding = Collections.nCopies(
                512,
                0.0
        );

        // when
        Throwable failure = catchThrowable(
                () -> speaker(
                        "a",
                        embedding,
                        1
                )
        );

        // then
        assertThat(failure).isInstanceOf(RecordingException.class);
    }

    @Test
    @DisplayName("화자 발화 프레임 수가 입력보다 크면 거절한다")
    void create_failure_voicedFramesBeyondInput() {
        // given
        AiWindowEvidence.Speaker speaker = speaker(
                "a",
                List.of(),
                101
        );

        // when
        Throwable failure = catchThrowable(
                () -> evidence(
                        List.of(),
                        List.of(speaker)
                )
        );

        // then
        assertThat(failure).isInstanceOf(RecordingException.class);
    }

    @Test
    @DisplayName("단어 개수 제한을 넘는 결과는 거절한다")
    void create_failure_excessiveTokens() {
        // given
        List<AiWindowEvidence.Token> tokens = Collections.nCopies(
                20001,
                token(
                        0,
                        1,
                        "a"
                )
        );

        // when
        Throwable failure = catchThrowable(
                () -> evidence(
                        tokens,
                        List.of(
                                speaker(
                                        "a",
                                        List.of(),
                                        1
                                )
                        )
                )
        );

        // then
        assertThat(failure).isInstanceOf(RecordingException.class);
    }

    @Test
    @DisplayName("화자 개수 제한을 넘는 결과는 거절한다")
    void create_failure_excessiveSpeakers() {
        // given
        List<AiWindowEvidence.Speaker> speakers = new ArrayList<>();
        for (int index = 0; index < 65; index++) {
            speakers.add(
                    speaker(
                            "speaker" + index,
                            List.of(),
                            1
                    )
            );
        }

        // when
        Throwable failure = catchThrowable(
                () -> evidence(
                        List.of(),
                        speakers
                )
        );

        // then
        assertThat(failure).isInstanceOf(RecordingException.class);
    }

    @Test
    @DisplayName("빈 단어 텍스트는 거절한다")
    void token_failure_blankText() {
        // given
        String text = " ";

        // when
        Throwable failure = catchThrowable(
                () -> new AiWindowEvidence.Token(
                        0,
                        1,
                        text,
                        "a"
                )
        );

        // then
        assertThat(failure).isInstanceOf(RecordingException.class);
    }

    @Test
    @DisplayName("임베딩 없는 미확정 화자는 발화 프레임 수가 0이어도 텍스트를 보존한다")
    void create_success_unknownSpeakerWithZeroVoicedFrames() {
        // given
        AiWindowEvidence.Speaker unknown = speaker(
                "unknown",
                List.of(),
                0
        );

        // when
        AiWindowEvidence result = evidence(
                List.of(
                        token(
                                0,
                                10,
                                "unknown"
                        )
                ),
                List.of(unknown)
        );

        // then
        assertThat(result.tokens()).hasSize(1);
        assertThat(
                result.speakers()
                        .getFirst()
                        .voicedFrames()
        ).isZero();
    }

    @Test
    @DisplayName("유효 임베딩이 있어도 발화 프레임 수가 0이면 화자 근거를 거절한다")
    void speaker_failure_knownSpeakerWithZeroVoicedFrames() {
        // given
        List<Double> embedding = Collections.nCopies(
                512,
                1.0
        );

        // when
        Throwable failure = catchThrowable(
                () -> speaker(
                        "known",
                        embedding,
                        0
                )
        );

        // then
        assertThat(failure).isInstanceOf(RecordingException.class);
    }

    private AiWindowEvidence evidence(
            List<AiWindowEvidence.Token> tokens,
            List<AiWindowEvidence.Speaker> speakers
    ) {
        return new AiWindowEvidence(
                1,
                "v1",
                HASH,
                0,
                100,
                "model",
                tokens,
                speakers
        );
    }

    private AiWindowEvidence.Token token(
            long start,
            long end,
            String speakerId
    ) {
        return new AiWindowEvidence.Token(
                start,
                end,
                "단어",
                speakerId
        );
    }

    private AiWindowEvidence.Speaker speaker(
            String speakerId,
            List<Double> embedding,
            long voicedFrames
    ) {
        return new AiWindowEvidence.Speaker(
                speakerId,
                embedding,
                voicedFrames
        );
    }

    @Test
    @DisplayName("AI의 화자 근거 조각과 segment 번호를 저장할 JSON에 그대로 보존한다")
    void serialize_success_observationsRoundTrip() {
        // given
        ObjectMapper mapper = new ObjectMapper();
        AiWindowEvidence evidence = new AiWindowEvidence(
                1,
                "v1",
                HASH,
                0,
                32000,
                "model",
                List.of(
                        new AiWindowEvidence.Token(
                                0,
                                8000,
                                "첫",
                                "s",
                                0
                        )
                ),
                List.of(
                        new AiWindowEvidence.Speaker(
                                "s",
                                List.of(),
                                0
                        )
                ),
                List.of(
                        new AiWindowEvidence.Observation(
                                0,
                                24000,
                                "s",
                                Collections.nCopies(
                                        512,
                                        .333333333
                                )
                        )
                )
        );

        // when
        AiWindowEvidence restored = mapper.readValue(
                mapper.writeValueAsString(evidence),
                AiWindowEvidence.class
        );

        // then
        assertThat(restored).isEqualTo(evidence);
    }

    @Test
    @DisplayName("이전 AI 결과처럼 화자 근거 조각이 없으면 빈 목록으로 읽는다")
    void deserialize_success_withoutObservations() {
        // given
        String json = """
                {"schemaVersion":1,"pipelineVersion":"v1","inputManifestSha256":"%s","inputStartFrame":0,
                "inputEndFrame":16000,"modelVersion":"model","tokens":[],"speakers":[]}
                """.formatted(HASH);

        // when
        AiWindowEvidence evidence = new ObjectMapper().readValue(
                json,
                AiWindowEvidence.class
        );

        // then
        assertThat(evidence.observations()).isEmpty();
    }

    @Test
    @DisplayName("입력 밖이거나 모르는 화자의 근거 조각은 거절한다")
    void create_failure_invalidObservation() {
        // given
        List<Double> vector = Collections.nCopies(
                512,
                .1
        );

        // when
        Throwable outside = catchThrowable(
                () -> observed(
                        new AiWindowEvidence.Observation(
                                0,
                                16001,
                                "s",
                                vector
                        )
                )
        );
        Throwable unknown = catchThrowable(
                () -> observed(
                        new AiWindowEvidence.Observation(
                                0,
                                8000,
                                "other",
                                vector
                        )
                )
        );
        Throwable shortVector = catchThrowable(
                () -> new AiWindowEvidence.Observation(
                        0,
                        8000,
                        "s",
                        List.of(.1)
                )
        );

        // then
        assertThat(outside).isInstanceOf(RecordingException.class);
        assertThat(unknown).isInstanceOf(RecordingException.class);
        assertThat(shortVector).isInstanceOf(RecordingException.class);
    }

    private AiWindowEvidence observed(AiWindowEvidence.Observation observation) {
        return new AiWindowEvidence(
                1,
                "v1",
                HASH,
                0,
                16000,
                "model",
                List.of(),
                List.of(
                        new AiWindowEvidence.Speaker(
                                "s",
                                List.of(),
                                0
                        )
                ),
                List.of(observation)
        );
    }
}
