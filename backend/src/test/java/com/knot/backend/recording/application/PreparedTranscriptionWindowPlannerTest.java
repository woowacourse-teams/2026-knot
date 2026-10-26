package com.knot.backend.recording.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.knot.backend.recording.application.dto.result.PreparedWindowSource;
import com.knot.backend.recording.domain.RecordingException;
import com.knot.backend.recording.domain.model.RecordingTranscriptionWindow;
import com.knot.backend.recording.domain.model.TranscriptionDecodePolicy;
import com.knot.backend.recording.domain.model.TranscriptionWindowShape;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class PreparedTranscriptionWindowPlannerTest {
    private static final Instant NOW = Instant.parse("2026-10-18T00:00:00Z");
    private static final String PIPELINE = "prepared-window-v1-30-5";
    private static final String CHECKSUM = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=";
    private static final String KEY = "recordings/1/prepared/00000000-0000-0000-0000-000000000001/" + "0".repeat(64)
            + ".wav";
    private final ObjectMapper mapper = new ObjectMapper();
    private final RecordingTranscriptionWindowPlanner planner = new RecordingTranscriptionWindowPlanner(mapper);

    @Test
    @DisplayName("준비 WAV도 2분 구간과 직전 10초로 나누고 마지막 구간만 기본 디코딩으로 요청한다")
    void planPrepared_success_twoMinuteWindows() {
        // given
        PreparedWindowSource source = new PreparedWindowSource(
                KEY,
                "prepared-v1",
                CHECKSUM,
                44 + 250 * 32_000L,
                250 * 16_000L
        );

        // when
        List<RecordingTranscriptionWindow> windows = planner.planPrepared(
                1,
                TranscriptionWindowShape.PREPARED_TWO_MINUTES,
                source,
                NOW
        );

        // then
        assertThat(windows).extracting(RecordingTranscriptionWindow::getInputStartFrame)
                .containsExactly(
                        0L,
                        110 * 16_000L,
                        230 * 16_000L
                );
        assertThat(windows).extracting(RecordingTranscriptionWindow::getDecodePolicy)
                .containsExactly(
                        TranscriptionDecodePolicy.WITH_VOCABULARY,
                        TranscriptionDecodePolicy.WITH_VOCABULARY,
                        TranscriptionDecodePolicy.DEFAULT_ONLY
                );
    }

    @Test
    @DisplayName("준비 WAV 전체를 30초 core와 5초 문맥으로 나누고 마지막 짧은 구간까지 포함한다")
    void planPrepared_success_completeCoverageAndTail() {
        // given
        PreparedWindowSource source = new PreparedWindowSource(
                KEY,
                "prepared-v1",
                CHECKSUM,
                1_120_044,
                560_000
        );

        // when
        List<RecordingTranscriptionWindow> windows = planner.planPrepared(
                1,
                PIPELINE,
                480_000,
                80_000,
                source,
                NOW
        );

        // then
        assertThat(windows).hasSize(2);
        assertThat(windows).extracting(RecordingTranscriptionWindow::getCoreStartFrame)
                .containsExactly(
                        0L,
                        480_000L
                );
        assertThat(windows).extracting(RecordingTranscriptionWindow::getCoreEndFrame)
                .containsExactly(
                        480_000L,
                        560_000L
                );
        assertThat(windows).extracting(RecordingTranscriptionWindow::getInputStartFrame)
                .containsExactly(
                        0L,
                        400_000L
                );
        assertThat(windows).extracting(RecordingTranscriptionWindow::getInputEndFrame)
                .containsExactly(
                        560_000L,
                        560_000L
                );
        JsonNode manifest = mapper.readTree(
                windows.getLast()
                        .getManifestJson()
        );
        assertThat(
                manifest.path("schemaVersion")
                        .asInt()
        ).isEqualTo(2);
        assertThat(
                manifest.path("sourceKind")
                        .asString()
        ).isEqualTo("PREPARED_WAV");
        assertThat(
                manifest.path("source")
                        .path("storageKey")
                        .asString()
        ).isEqualTo(KEY);
        assertThat(
                manifest.path("source")
                        .path("versionId")
                        .asString()
        ).isEqualTo("prepared-v1");
        assertThat(manifest.has("parts")).isFalse();
    }

    @Test
    @DisplayName("준비 원본이 같으면 재계획해도 manifest hash가 같다")
    void planPrepared_success_stableManifest() {
        // given
        PreparedWindowSource source = new PreparedWindowSource(
                KEY,
                "prepared-v1",
                CHECKSUM,
                64_044,
                32_000
        );
        List<RecordingTranscriptionWindow> original = planner.planPrepared(
                1,
                PIPELINE,
                16_000,
                0,
                source,
                NOW
        );

        // when
        List<RecordingTranscriptionWindow> replayed = planner.planPrepared(
                1,
                PIPELINE,
                16_000,
                0,
                source,
                NOW.plusSeconds(1)
        );

        // then
        assertThat(replayed).extracting(RecordingTranscriptionWindow::getManifestSha256)
                .containsExactlyElementsOf(
                        original.stream()
                                .map(RecordingTranscriptionWindow::getManifestSha256)
                                .toList()
                );
    }

    @ParameterizedTest
    @ValueSource(strings = {"version", "key", "length", "frames", "checksum"})
    @DisplayName("준비 원본의 recording 격리와 고정 버전·WAV 길이를 검증한다")
    void planPrepared_failure_invalidSource(String condition) {
        // given
        PreparedWindowSource source = new PreparedWindowSource(
                condition.equals("key")
                        ? KEY.replace(
                                "recordings/1/",
                                "recordings/2/"
                        )
                        : KEY,
                condition.equals("version") ? "null" : "prepared-v1",
                condition.equals("checksum") ? "not-a-checksum" : CHECKSUM,
                condition.equals("length") ? 64_043 : 64_044,
                condition.equals("frames") ? 0 : 32_000
        );

        // when
        Throwable failure = catchThrowable(
                () -> planner.planPrepared(
                        1,
                        PIPELINE,
                        16_000,
                        0,
                        source,
                        NOW
                )
        );

        // then
        assertThat(failure).isInstanceOf(RecordingException.class);
    }
}
