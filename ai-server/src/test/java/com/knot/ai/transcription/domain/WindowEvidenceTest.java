package com.knot.ai.transcription.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

class WindowEvidenceTest {
    @Test
    void silentWindowIsAllowedWhileLegacyEmptyResultIsRejected() {
        // given
        WindowEvidence window = new WindowEvidence(
                1,
                "v1",
                "a".repeat(64),
                0,
                16000,
                "model",
                List.of(),
                List.of()
        );
        // when
        TranscriptResult result = new TranscriptResult(
                1,
                "{\"transcription\":[]}",
                List.of(),
                window
        );
        // then
        assertThat(result.utterances()).isEmpty();
        assertThatThrownBy(
                () -> new TranscriptResult(
                        1,
                        "{}",
                        List.of()
                )
        ).isInstanceOf(TranscriptionException.class);
    }

    @Test
    void rejectsNonfiniteEmbeddingAndTokenOutsideInput() {
        // given
        List<Double> vector = Collections.nCopies(
                512,
                Double.NaN
        );
        // when
        Throwable invalid = org.assertj.core.api.Assertions.catchThrowable(
                () -> new WindowEvidence.Speaker(
                        "s",
                        vector,
                        16000
                )
        );
        // then
        assertThat(invalid).isInstanceOf(TranscriptionException.class);
        assertThatThrownBy(
                () -> new WindowEvidence(
                        1,
                        "v1",
                        "a".repeat(64),
                        0,
                        16000,
                        "model",
                        List.of(
                                new WindowEvidence.Token(
                                        0,
                                        16001,
                                        "word",
                                        "s"
                                )
                        ),
                        List.of(
                                new WindowEvidence.Speaker(
                                        "s",
                                        List.of(),
                                        16000
                                )
                        )
                )
        ).isInstanceOf(TranscriptionException.class);
    }

    @Test
    @org.junit.jupiter.api.DisplayName("runner 결과의 화자 근거 조각과 Whisper segment 번호를 그대로 보존한다")
    void deserialize_success_observationsAndSegments() throws Exception {
        // given
        String vector = String.join(
                ",",
                Collections.nCopies(
                        512,
                        "0.333333333"
                )
        );
        String json = """
                {"schemaVersion":1,"pipelineVersion":"v1","inputManifestSha256":"%s","inputStartFrame":0,
                "inputEndFrame":32000,"modelVersion":"model",
                "tokens":[{"startFrame":0,"endFrame":8000,"text":"첫","speakerId":"s","segment":0}],
                "speakers":[{"speakerId":"s","embedding":[],"voicedFrames":16000}],
                "observations":[{"startFrame":0,"endFrame":24000,"speakerId":"s","embedding":[%s]}]}
                """.formatted(
                "a".repeat(64),
                vector
        );

        // when
        WindowEvidence window = tools.jackson.databind.json.JsonMapper.builder()
                .build()
                .readValue(
                        json,
                        WindowEvidence.class
                );

        // then
        assertThat(
                window.tokens()
                        .getFirst()
                        .segment()
        ).isZero();
        assertThat(window.observations()).extracting(WindowEvidence.Observation::endFrame)
                .containsExactly(24000L);
    }

    @Test
    @org.junit.jupiter.api.DisplayName("입력 밖이거나 모르는 화자의 근거 조각은 거절한다")
    void create_failure_invalidObservation() {
        // given
        List<Double> vector = Collections.nCopies(
                512,
                .1
        );
        List<WindowEvidence.Speaker> speakers = List.of(
                new WindowEvidence.Speaker(
                        "s",
                        List.of(),
                        0
                )
        );

        // when
        Throwable outside = org.assertj.core.api.Assertions.catchThrowable(
                () -> evidence(
                        speakers,
                        new WindowEvidence.Observation(
                                0,
                                16001,
                                "s",
                                vector
                        )
                )
        );
        Throwable unknown = org.assertj.core.api.Assertions.catchThrowable(
                () -> evidence(
                        speakers,
                        new WindowEvidence.Observation(
                                0,
                                8000,
                                "other",
                                vector
                        )
                )
        );
        Throwable shortVector = org.assertj.core.api.Assertions.catchThrowable(
                () -> new WindowEvidence.Observation(
                        0,
                        8000,
                        "s",
                        List.of(.1)
                )
        );

        // then
        assertThat(outside).isInstanceOf(TranscriptionException.class);
        assertThat(unknown).isInstanceOf(TranscriptionException.class);
        assertThat(shortVector).isInstanceOf(TranscriptionException.class);
    }

    private WindowEvidence evidence(
            List<WindowEvidence.Speaker> speakers,
            WindowEvidence.Observation observation
    ) {
        return new WindowEvidence(
                1,
                "v1",
                "a".repeat(64),
                0,
                16000,
                "model",
                List.of(),
                speakers,
                List.of(observation)
        );
    }
}
