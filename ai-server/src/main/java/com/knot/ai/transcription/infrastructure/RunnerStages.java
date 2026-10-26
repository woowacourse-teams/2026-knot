package com.knot.ai.transcription.infrastructure;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * runner가 남긴 단계별 처리 시간 중 승인한 숫자 항목만 읽는다. 없거나 형식이 틀리면 전체를 버려 전사 결과에 영향을 주지 않는다.
 */
final class RunnerStages {
    private static final long MAXIMUM_BYTES = 4096;
    private static final long MAXIMUM_MILLIS = 3_600_000L;
    private static final int MAXIMUM_DECODES = 3;
    private static final String DECODE_COUNT = "decodeCount";
    private static final List<String> REQUIRED = List.of(
            DECODE_COUNT,
            "defaultDecodeMs",
            "diarizationMs"
    );
    private static final List<String> DURATIONS = List.of(
            "defaultDecodeMs",
            "diarizationMs",
            "retryDecodeMs",
            "vocabularyDecodeMs"
    );
    private static final List<String> APPROVED = List.of(
            DECODE_COUNT,
            "defaultDecodeMs",
            "diarizationMs",
            "retryRan",
            "retryAdopted",
            "retryDecodeMs",
            "vocabularyRan",
            "vocabularyDecodeMs"
    );

    private RunnerStages() {}

    static Optional<Map<String, Object>> read(
            Path directory,
            JsonMapper mapper
    ) {
        Path file = directory.resolve("stages.json");
        try {
            if (!Files.isRegularFile(
                    file,
                    LinkOption.NOFOLLOW_LINKS
            ) || Files.size(file) > MAXIMUM_BYTES) {
                return Optional.empty();
            }
            return approved(mapper.readTree(file.toFile()));
        } catch (IOException | RuntimeException exception) {
            return Optional.empty();
        }
    }

    private static Optional<Map<String, Object>> approved(JsonNode stages) {
        if (!stages.isObject() || REQUIRED.stream()
                .anyMatch(name -> !stages.has(name))) {
            return Optional.empty();
        }
        Map<String, Object> values = new LinkedHashMap<>();
        for (String name : APPROVED) {
            Optional<Object> value = value(
                    name,
                    stages.get(name)
            );
            value.ifPresent(
                    parsed -> values.put(
                            name,
                            parsed
                    )
            );
        }
        if (values.size() != stages.size()) {
            return Optional.empty();
        }
        return Optional.of(values);
    }

    private static Optional<Object> value(
            String name,
            JsonNode value
    ) {
        if (value == null) {
            return Optional.empty();
        }
        if (DECODE_COUNT.equals(name)) {
            return decodeCount(value);
        }
        if (DURATIONS.contains(name)) {
            return duration(value);
        }
        return flag(value);
    }

    private static Optional<Object> decodeCount(JsonNode value) {
        if (!value.isIntegralNumber() || !value.canConvertToInt() || value.intValue() < 1
                || value.intValue() > MAXIMUM_DECODES) {
            return Optional.empty();
        }
        return Optional.of(value.intValue());
    }

    private static Optional<Object> duration(JsonNode value) {
        if (!value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() < 0
                || value.longValue() > MAXIMUM_MILLIS) {
            return Optional.empty();
        }
        return Optional.of(value.longValue());
    }

    private static Optional<Object> flag(JsonNode value) {
        if (!value.isBoolean()) {
            return Optional.empty();
        }
        return Optional.of(value.booleanValue());
    }
}
