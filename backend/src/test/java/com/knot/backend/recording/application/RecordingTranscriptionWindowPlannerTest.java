package com.knot.backend.recording.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.knot.backend.recording.domain.RecordingException;
import com.knot.backend.recording.domain.model.AudioChunkDescriptor;
import com.knot.backend.recording.domain.model.AudioManifestPart;
import com.knot.backend.recording.domain.model.RecordingAudioChunk;
import com.knot.backend.recording.domain.model.RecordingTranscriptionWindow;
import com.knot.backend.recording.domain.model.StoredAudioChunk;
import com.knot.backend.recording.domain.model.TranscriptionDecodePolicy;
import com.knot.backend.recording.domain.model.TranscriptionWindowShape;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class RecordingTranscriptionWindowPlannerTest {
    private static final Instant NOW = Instant.parse("2026-10-18T01:00:00Z");
    private static final String CHECKSUM = Base64.getEncoder()
            .encodeToString(new byte[32]);

    private final ObjectMapper mapper = new ObjectMapper();
    private final RecordingTranscriptionWindowPlanner planner = new RecordingTranscriptionWindowPlanner(mapper);

    @Test
    @DisplayName("핵심 구간과 오른쪽 문맥이 모두 저장되면 완전한 구간만 계획한다")
    void plan_success_fullCoreAndContext() {
        // given
        List<RecordingAudioChunk> chunks = chunks(7);

        // when
        List<RecordingTranscriptionWindow> windows = planner.plan(
                1,
                "v1",
                32_000,
                16_000,
                chunks,
                NOW
        );

        // then
        assertThat(windows).hasSize(3);
        assertThat(windows).extracting(RecordingTranscriptionWindow::getWindowIndex)
                .containsExactly(
                        0,
                        1,
                        2
                );
        assertThat(windows).extracting(RecordingTranscriptionWindow::getCoreStartFrame)
                .containsExactly(
                        0L,
                        32_000L,
                        64_000L
                );
        assertThat(windows).extracting(RecordingTranscriptionWindow::getInputStartFrame)
                .containsExactly(
                        0L,
                        16_000L,
                        48_000L
                );
        assertThat(windows).extracting(RecordingTranscriptionWindow::getInputEndFrame)
                .containsExactly(
                        48_000L,
                        80_000L,
                        112_000L
                );
    }

    @Test
    @DisplayName("핵심 구간은 저장됐어도 오른쪽 문맥이 부족하면 계획하지 않는다")
    void plan_failure_missingRightContext() {
        // given
        List<RecordingAudioChunk> chunks = chunks(2);

        // when
        List<RecordingTranscriptionWindow> windows = planner.plan(
                1,
                "v1",
                32_000,
                1,
                chunks,
                NOW
        );

        // then
        assertThat(windows).isEmpty();
    }

    @Test
    @DisplayName("문맥이 없으면 정확히 저장된 마지막 핵심 구간까지 계획한다")
    void plan_success_zeroContext() {
        // given
        List<RecordingAudioChunk> chunks = chunks(4);

        // when
        List<RecordingTranscriptionWindow> windows = planner.plan(
                1,
                "v1",
                32_000,
                0,
                chunks,
                NOW
        );

        // then
        assertThat(windows).hasSize(2);
        assertThat(
                windows.getLast()
                        .getCoreEndFrame()
        ).isEqualTo(64_000);
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "reserved", "offset", "sequence"})
    @DisplayName("누락과 예약 상태 또는 순서와 오프셋이 끊긴 위치에서 계획을 멈춘다")
    void plan_success_stopsAtBrokenPrefix(String condition) {
        // given
        List<RecordingAudioChunk> chunks = new ArrayList<>(chunks(2));
        switch (condition) {
            case "missing" -> chunks.add(
                    verified(
                            4,
                            48_000
                    )
            );
            case "reserved" -> chunks.add(
                    reserved(
                            3,
                            32_000
                    )
            );
            case "offset" -> chunks.add(
                    verified(
                            3,
                            32_001
                    )
            );
            default -> chunks.add(
                    verified(
                            2,
                            32_000
                    )
            );
        }
        chunks.add(
                verified(
                        5,
                        64_000
                )
        );

        // when
        List<RecordingTranscriptionWindow> windows = planner.plan(
                1,
                "v1",
                16_000,
                0,
                chunks,
                NOW
        );

        // then
        assertThat(windows).hasSize(2);
        assertThat(
                windows.getLast()
                        .getCoreEndFrame()
        ).isEqualTo(32_000);
    }

    @Test
    @DisplayName("첫 조각이 순서 1과 오프셋 0이 아니면 계획하지 않는다")
    void plan_failure_missingInitialChunk() {
        // given
        List<RecordingAudioChunk> chunks = List.of(
                verified(
                        2,
                        16_000
                )
        );

        // when
        List<RecordingTranscriptionWindow> windows = planner.plan(
                1,
                "v1",
                16_000,
                0,
                chunks,
                NOW
        );

        // then
        assertThat(windows).isEmpty();
    }

    @Test
    @DisplayName("매니페스트는 입력 범위와 겹치는 정확한 조각 버전과 체크섬을 고정한다")
    void plan_success_exactStorageManifest() {
        // given
        List<RecordingAudioChunk> chunks = chunks(5);

        // when
        List<RecordingTranscriptionWindow> windows = planner.plan(
                1,
                "v1",
                32_000,
                8_000,
                chunks,
                NOW
        );

        // then
        JsonNode manifest = mapper.readTree(
                windows.get(1)
                        .getManifestJson()
        );
        assertThat(manifest.isObject()).isTrue();
        assertThat(
                manifest.get("schemaVersion")
                        .asInt()
        ).isEqualTo(1);
        assertThat(
                manifest.get("inputStartFrame")
                        .asLong()
        ).isEqualTo(24_000);
        assertThat(
                manifest.get("inputEndFrame")
                        .asLong()
        ).isEqualTo(72_000);
        JsonNode parts = manifest.get("parts");
        assertThat(parts.size()).isEqualTo(4);
        assertThat(
                parts.get(0)
                        .get("sequence")
                        .asInt()
        ).isEqualTo(2);
        assertThat(
                parts.get(0)
                        .get("sampleOffset")
                        .asLong()
        ).isEqualTo(16_000);
        assertThat(
                parts.get(0)
                        .get("frameCount")
                        .asInt()
        ).isEqualTo(16_000);
        assertThat(
                parts.get(0)
                        .get("storageKey")
                        .asText()
        ).isEqualTo("recordings/1/chunks/2.pcm");
        assertThat(
                parts.get(0)
                        .get("checksumSha256")
                        .asText()
        ).isEqualTo(CHECKSUM);
        assertThat(
                parts.get(0)
                        .get("versionId")
                        .asText()
        ).isEqualTo("version-2");
        assertThat(
                parts.get(3)
                        .get("sequence")
                        .asInt()
        ).isEqualTo(5);
    }

    @Test
    @DisplayName("같은 저장 조각의 반복 계획은 매니페스트가 같고 시도 식별자만 새로 만든다")
    void plan_success_repeatableIdentity() {
        // given
        List<RecordingAudioChunk> chunks = chunks(1);
        RecordingTranscriptionWindow existing = planner.plan(
                1,
                "v1",
                16_000,
                0,
                chunks,
                NOW
        )
                .getFirst();

        // when
        RecordingTranscriptionWindow repeated = planner.plan(
                1,
                "v1",
                16_000,
                0,
                chunks,
                NOW
        )
                .getFirst();

        // then
        assertThat(repeated.getManifestJson()).isEqualTo(existing.getManifestJson());
        assertThat(repeated.getManifestSha256()).isEqualTo(existing.getManifestSha256());
        assertThat(repeated.getAttemptId()).isNotEqualTo(existing.getAttemptId());
    }

    @ParameterizedTest
    @CsvSource({"0,0", "15999,0", "115200001,0", "16000,-1", "16000,115200001"})
    @DisplayName("핵심 길이와 문맥 길이가 허용 범위를 벗어나면 거절한다")
    void plan_failure_invalidFrameConfiguration(
            long coreFrames,
            long contextFrames
    ) {
        // given
        List<RecordingAudioChunk> chunks = List.of();

        // when
        Throwable failure = catchThrowable(
                () -> planner.plan(
                        1,
                        "v1",
                        coreFrames,
                        contextFrames,
                        chunks,
                        NOW
                )
        );

        // then
        assertThat(failure).isInstanceOf(RecordingException.class);
    }

    @Test
    @DisplayName("다른 녹음의 저장 조각으로 전사 구간을 만들지 않는다")
    void plan_failure_recordingMismatch() {
        // given
        List<RecordingAudioChunk> chunks = chunks(1);

        // when
        Throwable failure = catchThrowable(
                () -> planner.plan(
                        2,
                        "v1",
                        16_000,
                        0,
                        chunks,
                        NOW
                )
        );

        // then
        assertThat(failure).isInstanceOf(RecordingException.class);
    }

    @Test
    @DisplayName("저장 조각이 없으면 빈 계획을 반환한다")
    void plan_success_emptyChunks() {
        // given
        List<RecordingAudioChunk> chunks = List.of();

        // when
        List<RecordingTranscriptionWindow> windows = planner.plan(
                1,
                "v1",
                16_000,
                0,
                chunks,
                NOW
        );

        // then
        assertThat(windows).isEmpty();
    }

    @Test
    @DisplayName("종료 계획은 남은 짧은 tail과 잘린 오른쪽 문맥을 포함한다")
    void planFinal_success_includesTail() {
        // given
        List<AudioManifestPart> parts = manifest(chunks(5));

        // when
        List<RecordingTranscriptionWindow> windows = planner.planFinal(
                1,
                "v1",
                32_000,
                16_000,
                80_000,
                parts,
                NOW
        );

        // then
        assertThat(windows).hasSize(3);
        assertThat(
                windows.getLast()
                        .getCoreStartFrame()
        ).isEqualTo(64_000);
        assertThat(
                windows.getLast()
                        .getCoreEndFrame()
        ).isEqualTo(80_000);
        assertThat(
                windows.getLast()
                        .getInputStartFrame()
        ).isEqualTo(48_000);
        assertThat(
                windows.getLast()
                        .getInputEndFrame()
        ).isEqualTo(80_000);
    }

    @Test
    @DisplayName("종료 전에 계획된 완전한 구간의 manifest와 hash를 그대로 재현한다")
    void planFinal_success_preservesExistingManifest() {
        // given
        List<RecordingAudioChunk> chunks = chunks(7);
        List<RecordingTranscriptionWindow> active = planner.plan(
                1,
                "v1",
                32_000,
                16_000,
                chunks,
                NOW
        );

        // when
        List<RecordingTranscriptionWindow> ended = planner.planFinal(
                1,
                "v1",
                32_000,
                16_000,
                112_000,
                manifest(chunks),
                NOW
        );

        // then
        assertThat(ended).hasSize(4);
        for (int index = 0; index < active.size(); index++) {
            assertThat(
                    ended.get(index)
                            .getManifestJson()
            ).isEqualTo(
                    active.get(index)
                            .getManifestJson()
            );
            assertThat(
                    ended.get(index)
                            .getManifestSha256()
            ).isEqualTo(
                    active.get(index)
                            .getManifestSha256()
            );
        }
    }

    @Test
    @DisplayName("종료 때 고정한 누락 조각은 version null로 남겨 무음 복원을 허용한다")
    void planFinal_success_preservesMissingVersion() {
        // given
        List<AudioManifestPart> parts = new ArrayList<>(manifest(chunks(2)));
        AudioManifestPart original = parts.getLast();
        parts.set(
                1,
                new AudioManifestPart(
                        original.sequence(),
                        original.sampleOffset(),
                        original.frameCount(),
                        original.storageKey(),
                        original.checksumSha256(),
                        null
                )
        );

        // when
        List<RecordingTranscriptionWindow> windows = planner.planFinal(
                1,
                "v1",
                32_000,
                0,
                32_000,
                parts,
                NOW
        );

        // then
        JsonNode stored = mapper.readTree(
                windows.getFirst()
                        .getManifestJson()
        )
                .path("parts");
        assertThat(
                stored.get(1)
                        .path("versionId")
                        .isNull()
        ).isTrue();
        assertThat(
                windows.getFirst()
                        .getCoreEndFrame()
        ).isEqualTo(32_000);
    }

    @ParameterizedTest
    @ValueSource(strings = {"offset", "sequence", "length", "version", "recording"})
    @DisplayName("종료 manifest의 coverage와 녹음 격리 또는 version이 잘못되면 거절한다")
    void planFinal_failure_invalidFrozenManifest(String condition) {
        // given
        AudioManifestPart original = manifest(chunks(1)).getFirst();
        List<AudioManifestPart> parts = List.of(
                new AudioManifestPart(
                        condition.equals("sequence") ? 2 : 1,
                        condition.equals("offset") ? 1 : 0,
                        condition.equals("length") ? 15_999 : 16_000,
                        condition.equals("recording") ? "recordings/2/chunks/1.pcm" : original.storageKey(),
                        original.checksumSha256(),
                        condition.equals("version") ? "null" : original.versionId()
                )
        );

        // when
        Throwable failure = catchThrowable(
                () -> planner.planFinal(
                        1,
                        "v1",
                        16_000,
                        0,
                        16_000,
                        parts,
                        NOW
                )
        );

        // then
        assertThat(failure).isInstanceOf(RecordingException.class);
    }

    private List<AudioManifestPart> manifest(List<RecordingAudioChunk> chunks) {
        return chunks.stream()
                .map(
                        chunk -> new AudioManifestPart(
                                chunk.getSequence(),
                                chunk.getSampleOffset(),
                                chunk.getFrameCount(),
                                chunk.getStorageKey(),
                                chunk.getChecksumSha256(),
                                chunk.getStorageVersionId()
                        )
                )
                .toList();
    }

    @Test
    @DisplayName("2분 구간은 미래 음성 없이 직전 10초만 문맥으로 쓰고 녹음 중에는 모두 용어 디코딩을 요청한다")
    void plan_success_twoMinuteWindowsWithLookback() {
        // given
        List<RecordingAudioChunk> chunks = chunks(250);

        // when
        List<RecordingTranscriptionWindow> windows = planner.plan(
                1,
                TranscriptionWindowShape.LIVE_TWO_MINUTES,
                chunks,
                NOW
        );

        // then
        assertThat(windows).extracting(RecordingTranscriptionWindow::getInputStartFrame)
                .containsExactly(
                        0L,
                        110 * 16_000L
                );
        assertThat(windows).extracting(RecordingTranscriptionWindow::getInputEndFrame)
                .containsExactly(
                        120 * 16_000L,
                        240 * 16_000L
                );
        assertThat(windows).extracting(RecordingTranscriptionWindow::getPipelineVersion)
                .containsOnly("pcm-window-v2-120-10");
        assertThat(windows).extracting(RecordingTranscriptionWindow::getDecodePolicy)
                .containsOnly(TranscriptionDecodePolicy.WITH_VOCABULARY);
    }

    @Test
    @DisplayName("2분 구간의 종료 계획은 마지막 구간만 기본 디코딩으로 요청한다")
    void planFinal_success_twoMinuteLastWindowDefaultOnly() {
        // given
        List<AudioManifestPart> parts = manifest(chunks(250));

        // when
        List<RecordingTranscriptionWindow> windows = planner.planFinal(
                1,
                TranscriptionWindowShape.LIVE_TWO_MINUTES,
                250 * 16_000L,
                parts,
                NOW
        );

        // then
        assertThat(windows).extracting(RecordingTranscriptionWindow::getInputStartFrame)
                .containsExactly(
                        0L,
                        110 * 16_000L,
                        230 * 16_000L
                );
        assertThat(windows).extracting(RecordingTranscriptionWindow::getInputEndFrame)
                .containsExactly(
                        120 * 16_000L,
                        240 * 16_000L,
                        250 * 16_000L
                );
        assertThat(windows).extracting(RecordingTranscriptionWindow::getDecodePolicy)
                .containsExactly(
                        TranscriptionDecodePolicy.WITH_VOCABULARY,
                        TranscriptionDecodePolicy.WITH_VOCABULARY,
                        TranscriptionDecodePolicy.DEFAULT_ONLY
                );
    }

    @Test
    @DisplayName("녹음 중 등록한 2분 구간과 종료 계획의 같은 구간은 입력이 같다")
    void planFinal_success_twoMinuteManifestMatchesLiveWindow() {
        // given
        List<RecordingAudioChunk> chunks = chunks(240);
        RecordingTranscriptionWindow live = planner.plan(
                1,
                TranscriptionWindowShape.LIVE_TWO_MINUTES,
                chunks,
                NOW
        )
                .getLast();

        // when
        RecordingTranscriptionWindow ended = planner.planFinal(
                1,
                TranscriptionWindowShape.LIVE_TWO_MINUTES,
                240 * 16_000L,
                manifest(chunks),
                NOW
        )
                .getLast();

        // then
        assertThat(ended.getManifestSha256()).isEqualTo(live.getManifestSha256());
        assertThat(ended.getDecodePolicy()).isEqualTo(TranscriptionDecodePolicy.DEFAULT_ONLY);
    }

    @Test
    @DisplayName("30초 구간은 디코딩 정책을 정하지 않아 AI 요청 모양을 바꾸지 않는다")
    void planFinal_success_thirtySecondWindowsWithoutPolicy() {
        // given
        List<AudioManifestPart> parts = manifest(chunks(40));

        // when
        List<RecordingTranscriptionWindow> windows = planner.planFinal(
                1,
                TranscriptionWindowShape.LIVE_30_SECONDS,
                40 * 16_000L,
                parts,
                NOW
        );

        // then
        assertThat(windows).hasSize(2);
        assertThat(windows).extracting(RecordingTranscriptionWindow::getDecodePolicy)
                .containsOnlyNulls();
    }

    private List<RecordingAudioChunk> chunks(int count) {
        List<RecordingAudioChunk> chunks = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            chunks.add(
                    verified(
                            index + 1,
                            index * 16_000L
                    )
            );
        }
        return chunks;
    }

    private RecordingAudioChunk verified(
            int sequence,
            long offset
    ) {
        RecordingAudioChunk chunk = reserved(
                sequence,
                offset
        );
        chunk.verify(
                new StoredAudioChunk(
                        true,
                        32_000,
                        "application/octet-stream",
                        CHECKSUM,
                        "version-" + sequence
                ),
                NOW
        );
        return chunk;
    }

    private RecordingAudioChunk reserved(
            int sequence,
            long offset
    ) {
        return RecordingAudioChunk.reserve(
                1,
                new AudioChunkDescriptor(
                        sequence,
                        offset,
                        16_000,
                        32_000,
                        CHECKSUM
                ),
                "recordings/1/chunks/" + sequence + ".pcm",
                NOW.plusSeconds(900)
        );
    }
}
