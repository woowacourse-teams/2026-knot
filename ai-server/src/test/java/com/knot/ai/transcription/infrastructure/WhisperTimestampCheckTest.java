package com.knot.ai.transcription.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class WhisperTimestampCheckTest {
    static final String RESTORED_WHISPER = "import sys,json\n" + "prefix=sys.argv[sys.argv.index('-of')+1]\n"
            + "params={'model':'m','language':'ko','translate':False,'vad':True,'token_timestamps':'original'}\n"
            + "open(prefix+'.json','w').write(json.dumps({'params':params,'transcription':[]}))\n";
    private static final String UNPATCHED_WHISPER = "import sys,json\n" + "prefix=sys.argv[sys.argv.index('-of')+1]\n"
            + "params={'model':'m','language':'ko','translate':False}\n"
            + "open(prefix+'.json','w').write(json.dumps({'params':params,'transcription':[]}))\n";

    @TempDir
    Path directory;
    private final JsonMapper mapper = JsonMapper.builder()
            .build();

    @Test
    @DisplayName("원음 시각 표시를 출력하는 패치 CLI는 준비 상태로 본다")
    void restoresOriginalTimestamps_patchedCli_true() throws Exception {
        // given
        JsonNode profile = profile(
                script(
                        "whisper",
                        RESTORED_WHISPER
                ),
                null
        );
        // when
        boolean restored = new WhisperTimestampCheck(mapper).restoresOriginalTimestamps(profile);
        // then
        assertThat(restored).isTrue();
    }

    @Test
    @DisplayName("원음 시각 표시가 없는 기존 CLI는 준비되지 않은 것으로 본다")
    void restoresOriginalTimestamps_unpatchedCli_false() throws Exception {
        // given
        JsonNode profile = profile(
                script(
                        "whisper",
                        UNPATCHED_WHISPER
                ),
                null
        );
        // when
        boolean restored = new WhisperTimestampCheck(mapper).restoresOriginalTimestamps(profile);
        // then
        assertThat(restored).isFalse();
    }

    @Test
    @DisplayName("CLI가 실패하거나 JSON을 남기지 않으면 준비되지 않은 것으로 본다")
    void restoresOriginalTimestamps_failingCli_false() throws Exception {
        // given
        JsonNode profile = profile(
                script(
                        "whisper",
                        "import sys\nsys.exit(3)\n"
                ),
                null
        );
        // when
        boolean restored = new WhisperTimestampCheck(mapper).restoresOriginalTimestamps(profile);
        // then
        assertThat(restored).isFalse();
    }

    @Test
    @DisplayName("VAD를 명시적으로 끈 프로필은 CLI를 실행하지 않고 준비 상태로 본다")
    void restoresOriginalTimestamps_vadDisabled_skipsCli() throws Exception {
        // given
        Path marker = directory.resolve("ran");
        JsonNode profile = profile(
                script(
                        "whisper",
                        "import pathlib\npathlib.Path('" + marker + "').touch()\n"
                ),
                false
        );
        // when
        boolean restored = new WhisperTimestampCheck(mapper).restoresOriginalTimestamps(profile);
        // then
        assertThat(restored).isTrue();
        assertThat(marker).doesNotExist();
    }

    @Test
    @DisplayName("확인에 쓴 임시 음성과 결과를 남기지 않는다")
    void restoresOriginalTimestamps_any_removesTemporaryFiles() throws Exception {
        // given
        Path seen = directory.resolve("seen.txt");
        JsonNode profile = profile(
                script(
                        "whisper",
                        RESTORED_WHISPER + "open('" + seen + "','w').write(sys.argv[sys.argv.index('-f')+1])\n"
                ),
                null
        );
        // when
        new WhisperTimestampCheck(mapper).restoresOriginalTimestamps(profile);
        // then
        Path audio = Path.of(Files.readString(seen));
        assertThat(audio.getParent()).doesNotExist();
    }

    private JsonNode profile(
            Path whisper,
            Boolean vadEnabled
    ) {
        Map<String, Object> profile = new LinkedHashMap<>(
                Map.of(
                        "whisperBinary",
                        whisper.toString(),
                        "whisperModel",
                        whisper.toString(),
                        "vadModel",
                        whisper.toString(),
                        "sttThreads",
                        1
                )
        );
        if (vadEnabled != null) {
            profile.put(
                    "whisperVadEnabled",
                    vadEnabled
            );
        }
        return mapper.valueToTree(profile);
    }

    private Path script(
            String name,
            String body
    ) throws Exception {
        Path path = directory.resolve(name);
        Files.writeString(
                path,
                "#!/usr/bin/env python3\n" + body
        );
        Files.setPosixFilePermissions(
                path,
                PosixFilePermissions.fromString("rwx------")
        );
        return path;
    }
}
