package com.knot.ai.transcription.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

class RunnerStagesTest {
    private final JsonMapper mapper = JsonMapper.builder()
            .build();

    @TempDir
    Path directory;

    @Test
    @DisplayName("구간 전사의 디코딩 횟수, 재시도·용어 디코딩 여부, 단계별 시간을 그대로 읽는다")
    void read_success_windowStages() throws Exception {
        // given
        Files.writeString(
                directory.resolve("stages.json"),
                "{\"decodeCount\":3,\"defaultDecodeMs\":18000,\"diarizationMs\":21000,\"retryRan\":true,"
                        + "\"retryAdopted\":false,\"retryDecodeMs\":17000,\"vocabularyRan\":true,\"vocabularyDecodeMs\":16000}"
        );

        // when
        Optional<Map<String, Object>> stages = RunnerStages.read(
                directory,
                mapper
        );

        // then
        assertThat(stages).hasValueSatisfying(
                values -> assertThat(values).containsExactly(
                        Map.entry(
                                "decodeCount",
                                3
                        ),
                        Map.entry(
                                "defaultDecodeMs",
                                18000L
                        ),
                        Map.entry(
                                "diarizationMs",
                                21000L
                        ),
                        Map.entry(
                                "retryRan",
                                true
                        ),
                        Map.entry(
                                "retryAdopted",
                                false
                        ),
                        Map.entry(
                                "retryDecodeMs",
                                17000L
                        ),
                        Map.entry(
                                "vocabularyRan",
                                true
                        ),
                        Map.entry(
                                "vocabularyDecodeMs",
                                16000L
                        )
                )
        );
    }

    @Test
    @DisplayName("전체 파일 전사는 기본 디코딩과 화자 분리 시간만 읽는다")
    void read_success_wholeFileStages() throws Exception {
        // given
        Files.writeString(
                directory.resolve("stages.json"),
                "{\"decodeCount\":1,\"defaultDecodeMs\":0,\"diarizationMs\":900}"
        );

        // when
        Optional<Map<String, Object>> stages = RunnerStages.read(
                directory,
                mapper
        );

        // then
        assertThat(stages).hasValueSatisfying(
                values -> assertThat(values).containsOnlyKeys(
                        "decodeCount",
                        "defaultDecodeMs",
                        "diarizationMs"
                )
        );
    }

    @Test
    @DisplayName("runner가 단계 기록을 남기지 않았으면 읽지 않는다")
    void read_empty_missingFile() {
        // when
        Optional<Map<String, Object>> stages = RunnerStages.read(
                directory,
                mapper
        );

        // then
        assertThat(stages).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "[]",
            "{\"decodeCount\":1,\"defaultDecodeMs\":10}",
            "{\"decodeCount\":0,\"defaultDecodeMs\":10,\"diarizationMs\":10}",
            "{\"decodeCount\":4,\"defaultDecodeMs\":10,\"diarizationMs\":10}",
            "{\"decodeCount\":1,\"defaultDecodeMs\":-1,\"diarizationMs\":10}",
            "{\"decodeCount\":1,\"defaultDecodeMs\":1.5,\"diarizationMs\":10}",
            "{\"decodeCount\":1,\"defaultDecodeMs\":\"10\",\"diarizationMs\":10}",
            "{\"decodeCount\":1,\"defaultDecodeMs\":3600001,\"diarizationMs\":10}",
            "{\"decodeCount\":2,\"defaultDecodeMs\":10,\"diarizationMs\":10,\"retryRan\":\"true\"}",
            "{\"decodeCount\":1,\"defaultDecodeMs\":10,\"diarizationMs\":10,\"text\":\"안녕\"}",
            "not json"
    })
    @DisplayName("형식이 틀리거나 승인하지 않은 항목이 있으면 단계 기록 전체를 버린다")
    void read_empty_invalidStages(String body) throws Exception {
        // given
        Files.writeString(
                directory.resolve("stages.json"),
                body
        );

        // when
        Optional<Map<String, Object>> stages = RunnerStages.read(
                directory,
                mapper
        );

        // then
        assertThat(stages).isEmpty();
    }

    @Test
    @DisplayName("크기 제한을 넘는 단계 기록은 읽지 않는다")
    void read_empty_oversizedFile() throws Exception {
        // given
        Files.writeString(
                directory.resolve("stages.json"),
                "{\"decodeCount\":1,\"defaultDecodeMs\":10,\"diarizationMs\":10}" + " ".repeat(5000)
        );

        // when
        Optional<Map<String, Object>> stages = RunnerStages.read(
                directory,
                mapper
        );

        // then
        assertThat(stages).isEmpty();
    }
}
