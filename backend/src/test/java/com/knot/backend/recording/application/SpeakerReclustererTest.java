package com.knot.backend.recording.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SpeakerReclustererTest {
    @Test
    @DisplayName("다른 목소리의 조각은 다른 화자로, 같은 목소리의 조각은 같은 화자로 묶는다")
    void labels_success_separatesVoices() {
        // given
        List<float[]> embeddings = new ArrayList<>();
        for (int index = 0; index < 8; index++) {
            embeddings.add(
                    voice(
                            index % 2,
                            index
                    )
            );
        }

        // when
        int[] labels = SpeakerReclusterer.labels(embeddings);

        // then
        assertThat(labels).containsExactly(
                0,
                1,
                0,
                1,
                0,
                1,
                0,
                1
        );
    }

    @Test
    @DisplayName("상한보다 잘게 나뉜 같은 목소리는 중심이 가까우면 하나로 합친다")
    void labels_success_mergesSplitVoice() {
        // given
        List<float[]> embeddings = new ArrayList<>();
        for (int index = 0; index < 12; index++) {
            embeddings.add(
                    voice(
                            0,
                            index
                    )
            );
        }

        // when
        int[] labels = SpeakerReclusterer.labels(embeddings);

        // then
        assertThat(labels).containsOnly(0);
    }

    @Test
    @DisplayName("같은 입력은 언제나 같은 화자 번호를 낸다")
    void labels_success_deterministic() {
        // given
        List<float[]> embeddings = new ArrayList<>();
        for (int index = 0; index < 20; index++) {
            embeddings.add(
                    voice(
                            index % 3,
                            index
                    )
            );
        }

        // when
        int[] first = SpeakerReclusterer.labels(embeddings);
        int[] second = SpeakerReclusterer.labels(embeddings);

        // then
        assertThat(first).containsExactly(second);
    }

    @Test
    @DisplayName("조각이 없거나 하나면 군집하지 않는다")
    void labels_success_tinyInput() {
        // when
        int[] none = SpeakerReclusterer.labels(List.of());
        int[] one = SpeakerReclusterer.labels(
                List.of(
                        voice(
                                0,
                                0
                        )
                )
        );

        // then
        assertThat(none).isEmpty();
        assertThat(one).containsExactly(0);
    }

    /** 사람마다 다른 축을 쓰고, 조각마다 작은 잡음을 더한다. */
    private float[] voice(
            int person,
            int observation
    ) {
        float[] vector = new float[512];
        vector[person] = 1;
        vector[100 + observation % 50] = .05f;
        return vector;
    }
}
