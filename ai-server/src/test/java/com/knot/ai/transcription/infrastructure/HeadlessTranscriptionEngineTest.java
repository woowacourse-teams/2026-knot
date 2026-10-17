package com.knot.ai.transcription.infrastructure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import com.knot.ai.transcription.application.TranscriptionLog;
import org.slf4j.LoggerFactory;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;
import com.knot.ai.global.AiServerProperties;
import com.knot.ai.transcription.application.command.CreateTranscriptionCommand;
import com.knot.ai.transcription.domain.TranscriptionException;
import com.knot.ai.transcription.domain.TranscriptResult;
import com.sun.net.httpserver.HttpServer;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import tools.jackson.databind.json.JsonMapper;

@ExtendWith(OutputCaptureExtension.class)
class HeadlessTranscriptionEngineTest {
    @TempDir
    Path directory;
    private HttpServer server;
    private HeadlessTranscriptionEngine engine;
    private Path ffmpeg;
    private Path pnpm;
    private final List<ILoggingEvent> events = new CopyOnWriteArrayList<>();
    private final Logger logger = (Logger) LoggerFactory.getLogger(TranscriptionLog.class);
    private final AppenderBase<ILoggingEvent> appender = new AppenderBase<>() {
        @Override
        protected void append(ILoggingEvent event) {
            events.add(event);
        }
    };
    @BeforeEach
    void prepare() throws Exception {
        appender.start();
        logger.addAppender(appender);
        Path runner = directory.resolve("runner");
        Path scripts = runner.resolve("scripts");
        Files.createDirectories(scripts);
        Files.copy(
                Path.of("runner/scripts/serverSupervisor.py"),
                scripts.resolve("serverSupervisor.py")
        );
        Path asset = script(
                "asset",
                "import sys\n"
        );
        ffmpeg = script(
                "ffmpeg",
                "import sys,wave\nw=wave.open(sys.argv[-1],'wb')\nw.setnchannels(1)\n"
                        + "w.setsampwidth(2)\nw.setframerate(16000)\nw.writeframes(bytes(32000))\nw.close()\n"
        );
        pnpm = script(
                "pnpm",
                "import sys,json,pathlib,os\n" + "m=json.loads(pathlib.Path(sys.argv[-1]).read_text())\n"
                        + "p=pathlib.Path(m['outputDirectory'])\n"
                        + "r={'schemaVersion':1,'rawWhisperJson':'{\\\"transcription\\\":[]}',"
                        + "'utterances':[{'ord':0,'speakerLabel':'화자 1','startSec':0,'endSec':1,'text':'안녕'}]}\n"
                        + "(p/'result.tmp').write_text(json.dumps(r))\nos.replace(p/'result.tmp',p/'result.json')\n"
        );
        JsonMapper mapper = JsonMapper.builder()
                .build();
        Path profile = directory.resolve("profile.json");
        mapper.writeValue(
                profile.toFile(),
                Map.of(
                        "whisperBinary",
                        asset.toString(),
                        "diarizeBinary",
                        asset.toString(),
                        "whisperModel",
                        asset.toString(),
                        "vadModel",
                        asset.toString(),
                        "segmentationModel",
                        asset.toString(),
                        "embeddingModel",
                        asset.toString(),
                        "sttThreads",
                        1,
                        "diarizeThreads",
                        1
                )
        );
        prepareStorage();
        AiServerProperties properties = new AiServerProperties(
                "token",
                directory.resolve("jobs"),
                profile,
                runner,
                Set.of("test-bucket.s3.ap-northeast-2.amazonaws.com"),
                Duration.ofSeconds(2),
                Duration.ofSeconds(5),
                Duration.ofSeconds(5),
                "python3",
                pnpm.toString(),
                ffmpeg.toString()
        );
        S3AudioDownloader downloader = new S3AudioDownloader(
                properties,
                uri -> (HttpURLConnection) URI.create(
                        "http://127.0.0.1:" + server.getAddress()
                                .getPort() + uri.getPath()
                )
                        .toURL()
                        .openConnection()
        );
        engine = new HeadlessTranscriptionEngine(
                properties,
                downloader,
                mapper
        );
    }

    private void prepareStorage() throws Exception {
        server = HttpServer.create(
                new InetSocketAddress(
                        "127.0.0.1",
                        0
                ),
                0
        );
        server.createContext(
                "/recordings/audio.webm",
                exchange -> {
                    exchange.getResponseHeaders()
                            .add(
                                    "Content-Type",
                                    "audio/webm"
                            );
                    exchange.getResponseHeaders()
                            .add(
                                    "ETag",
                                    "\"fixed\""
                            );
                    exchange.sendResponseHeaders(
                            200,
                            3
                    );
                    exchange.getResponseBody()
                            .write(new byte[]{1, 2, 3});
                    exchange.close();
                }
        );
        server.start();
    }

    @AfterEach
    void close() {
        server.stop(0);
        logger.detachAppender(appender);
        appender.stop();
    }

    @Test
    @DisplayName("감독 프로세스로 변환과 runner를 실행하고 완성 결과만 공개한다")
    void transcribe_success_supervisedPipeline() throws Exception {
        // given
        CreateTranscriptionCommand command = command();
        // when
        TranscriptResult result = engine.transcribe(command);
        // then
        assertEquals(
                "안녕",
                result.utterances()
                        .getFirst()
                        .text()
        );
        assertThat(fields()).extracting(fields -> fields.get("event") + ":" + fields.get("outcome"))
                .containsExactly(
                        "recording.transcription.download:started",
                        "recording.transcription.download:completed",
                        "recording.transcription.decode:started",
                        "recording.transcription.decode:completed",
                        "recording.transcription.model:started",
                        "recording.transcription.model:completed",
                        "recording.transcription.result_check:started",
                        "recording.transcription.result_check:completed"
                );
        for (ILoggingEvent event : events) {
            assertThat(event.getFormattedMessage()).doesNotContain(
                    "https://",
                    "audio.webm",
                    "input.wav",
                    "profile",
                    "안녕"
            );
            assertThat(event.getThrowableProxy()).isNull();
            assertThat(fields(event)).containsEntry(
                    "attemptId",
                    command.attemptId()
                            .toString()
            );
        }
        engine.cleanup(command.attemptId());
        assertFalse(
                Files.exists(
                        directory.resolve("jobs")
                                .resolve(
                                        command.attemptId()
                                                .toString()
                                )
                )
        );
    }

    @Test
    @DisplayName("다운로드 실패는 안전한 코드로 기록하고 디코딩 단계에 진입하지 않는다")
    void transcribe_failure_downloadLogsKnownCodeWithoutUrl() throws Exception {
        // given
        CreateTranscriptionCommand command = new CreateTranscriptionCommand(
                UUID.randomUUID(),
                "https://secret.invalid/private-audio?token=secret-token",
                null
        );

        // when
        TranscriptionException failure = assertThrows(
                TranscriptionException.class,
                () -> engine.transcribe(command)
        );

        // then
        assertEquals(
                "INVALID_DOWNLOAD_URL",
                failure.getCode()
        );
        assertThat(fields()).anySatisfy(
                fields -> assertThat(fields).containsEntry(
                        "event",
                        "recording.transcription.download"
                )
                        .containsEntry(
                                "outcome",
                                "failed"
                        )
                        .containsEntry(
                                "errorCode",
                                "INVALID_DOWNLOAD_URL"
                        )
        );
        assertThat(fields()).noneSatisfy(
                fields -> assertThat(fields).containsEntry(
                        "event",
                        "recording.transcription.decode"
                )
        );
        for (ILoggingEvent event : events) {
            assertThat(event.getFormattedMessage()).doesNotContain(
                    "secret.invalid",
                    "private-audio",
                    "secret-token"
            );
        }
        engine.cleanup(command.attemptId());
    }

    @Test
    @DisplayName("runner가 원본만 출력하면 작업 성공으로 처리하지 않는다")
    void transcribe_failure_rawOnly() throws Exception {
        // given
        Files.writeString(
                pnpm,
                "#!/usr/bin/env python3\nimport sys,pathlib,json\n"
                        + "m=json.loads(pathlib.Path(sys.argv[-1]).read_text())\n"
                        + "r={'schemaVersion':1,'rawWhisperJson':'{}','utterances':[]}\n"
                        + "(pathlib.Path(m['outputDirectory'])/'result.json').write_text(json.dumps(r))\n"
        );
        CreateTranscriptionCommand command = command();
        // when
        TranscriptionException exception = assertThrows(
                TranscriptionException.class,
                () -> engine.transcribe(command)
        );
        // then
        assertEquals(
                "INVALID_RESULT",
                exception.getCode()
        );
        assertThat(fields()).anySatisfy(
                fields -> assertThat(fields).containsEntry(
                        "event",
                        "recording.transcription.result_check"
                )
                        .containsEntry(
                                "outcome",
                                "failed"
                        )
                        .containsEntry(
                                "errorCode",
                                "INVALID_RESULT"
                        )
        );
        engine.cleanup(command.attemptId());
    }

    @Test
    @DisplayName("디코딩 실행 제한 시간이 지나면 단계별 timeout을 반환한다")
    void transcribe_failure_decodeTimeout() throws Exception {
        // given
        Files.writeString(
                ffmpeg,
                "#!/usr/bin/env python3\nimport time\ntime.sleep(30)\n"
        );
        CreateTranscriptionCommand command = command();
        // when
        TranscriptionException exception = assertThrows(
                TranscriptionException.class,
                () -> engine.transcribe(command)
        );
        // then
        assertEquals(
                "DECODE_TIMEOUT",
                exception.getCode()
        );
        assertThat(fields()).anySatisfy(
                fields -> assertThat(fields).containsEntry(
                        "event",
                        "recording.transcription.decode"
                )
                        .containsEntry(
                                "outcome",
                                "failed"
                        )
                        .containsEntry(
                                "errorCode",
                                "DECODE_TIMEOUT"
                        )
        );
        assertThat(fields()).noneSatisfy(
                fields -> assertThat(fields).containsEntry(
                        "event",
                        "recording.transcription.model"
                )
        );
        engine.cleanup(command.attemptId());
    }

    @Test
    @DisplayName("변환기가 출력 상한을 지키지 않아 PCM이 2시간을 넘으면 추론 전에 거부한다")
    void transcribe_failure_decodedDurationExceeded() throws Exception {
        // given
        Path decoder = directory.resolve("ffmpeg");
        Files.writeString(
                decoder,
                "#!/usr/bin/env python3\nimport sys,struct\n" + "size=230400002\nf=open(sys.argv[-1],'wb')\n"
                        + "f.write(struct.pack('<4sI4s4sIHHIIHH4sI',b'RIFF',size+36,b'WAVE',b'fmt ',"
                        + "16,1,1,16000,32000,2,16,b'data',size))\nf.seek(size+43)\nf.write(bytes(1))\nf.close()\n"
        );
        CreateTranscriptionCommand command = command();
        // when
        TranscriptionException exception = assertThrows(
                TranscriptionException.class,
                () -> engine.transcribe(command)
        );
        // then
        assertEquals(
                "AUDIO_DURATION_EXCEEDED",
                exception.getCode()
        );
        engine.cleanup(command.attemptId());
    }

    @ParameterizedTest
    @ValueSource(longs = {16000, 115199999, 115200000, 115200001, 115216000})
    @DisplayName("짧은 오디오는 길이를 유지하고 2시간 초과 오디오는 앞 2시간만 runner에 전달한다")
    void transcribe_success_boundedAudioInput(long inputSamples) throws Exception {
        // given
        long expectedSamples = Math.min(
                inputSamples,
                115200000
        );
        Files.writeString(
                ffmpeg,
                """
                        #!/usr/bin/env python3
                        import sys, struct
                        duration_index = sys.argv.index('-t')
                        assert duration_index > sys.argv.index('-i') + 1
                        assert duration_index < len(sys.argv) - 2
                        samples = min(%d, int(sys.argv[duration_index + 1]) * 16000)
                        size = samples * 2
                        with open(sys.argv[-1], 'wb') as output:
                            output.write(struct.pack('<4sI4s4sIHHIIHH4sI', b'RIFF', size+36,
                                b'WAVE', b'fmt ', 16, 1, 1, 16000, 32000, 2, 16, b'data', size))
                            output.seek(size + 43)
                            output.write(bytes(1))
                        """.formatted(inputSamples)
        );
        Files.writeString(
                pnpm,
                """
                        #!/usr/bin/env python3
                        import sys, json, pathlib, wave
                        manifest = json.loads(pathlib.Path(sys.argv[-1]).read_text())
                        with wave.open(manifest['audioPath']) as audio:
                            assert audio.getparams()[:4] == (1, 2, 16000, %d)
                        result = {'schemaVersion': 1,
                            'rawWhisperJson': json.dumps({'transcription': []}), 'utterances': [
                            {'ord': 0, 'speakerLabel': '화자 1', 'startSec': 0, 'endSec': 1, 'text': '안녕'}]}
                        pathlib.Path(manifest['outputDirectory'], 'result.json').write_text(json.dumps(result))
                        """.formatted(expectedSamples)
        );
        CreateTranscriptionCommand command = command();
        // when
        TranscriptResult result = engine.transcribe(command);
        // then
        assertEquals(
                "안녕",
                result.utterances()
                        .getFirst()
                        .text()
        );
        assertArrayEquals(
                new byte[]{1, 2, 3},
                Files.readAllBytes(
                        directory.resolve("jobs")
                                .resolve(
                                        command.attemptId()
                                                .toString()
                                )
                                .resolve("input.webm")
                )
        );
        engine.cleanup(command.attemptId());
    }

    @Test
    @DisplayName("runner가 알려진 진단 코드를 남기면 허용된 작업 오류 코드로 반환한다")
    void transcribe_failure_knownRunnerDiagnostic() throws Exception {
        // given
        diagnosticRunner("STT_FAILED");
        CreateTranscriptionCommand command = command();
        // when
        TranscriptionException exception = assertThrows(
                TranscriptionException.class,
                () -> engine.transcribe(command)
        );
        // then
        assertEquals(
                "STT_FAILED",
                exception.getCode()
        );
        assertThat(fields()).anySatisfy(
                fields -> assertThat(fields).containsEntry(
                        "event",
                        "recording.transcription.model"
                )
                        .containsEntry(
                                "outcome",
                                "failed"
                        )
                        .containsEntry(
                                "errorCode",
                                "STT_FAILED"
                        )
        );
        engine.cleanup(command.attemptId());
    }

    @Test
    @DisplayName("runner의 CANCELLED 진단은 서버 오류 코드 CANCELED로 보정한다")
    void transcribe_failure_cancelledRunnerDiagnostic() throws Exception {
        // given
        diagnosticRunner("CANCELLED");
        CreateTranscriptionCommand command = command();
        // when
        TranscriptionException exception = assertThrows(
                TranscriptionException.class,
                () -> engine.transcribe(command)
        );
        // then
        assertEquals(
                "CANCELED",
                exception.getCode()
        );
        engine.cleanup(command.attemptId());
    }

    @Test
    @DisplayName("runner의 알 수 없는 진단은 추론 실패로 숨긴다")
    void transcribe_failure_unknownRunnerDiagnostic(CapturedOutput output) throws Exception {
        // given
        diagnosticRunner("SECRET_PATH_LEAK");
        CreateTranscriptionCommand command = command();
        // when
        TranscriptionException exception = assertThrows(
                TranscriptionException.class,
                () -> engine.transcribe(command)
        );
        // then
        assertEquals(
                "INFERENCE_FAILED",
                exception.getCode()
        );
        assertTrue(
                output.getOut()
                        .contains("Runner diagnostic code is unsupported")
        );
        assertFalse(
                output.getAll()
                        .contains("SECRET_PATH_LEAK")
        );
        engine.cleanup(command.attemptId());
    }

    @Test
    @DisplayName("진단 파일이 없으면 안전한 추론 실패와 누락 로그를 남긴다")
    void transcribe_failure_missingDiagnostic(CapturedOutput output) throws Exception {
        // given
        failingRunner("pass");
        CreateTranscriptionCommand command = command();
        // when
        TranscriptionException exception = assertThrows(
                TranscriptionException.class,
                () -> engine.transcribe(command)
        );
        // then
        assertEquals(
                "INFERENCE_FAILED",
                exception.getCode()
        );
        assertTrue(
                output.getOut()
                        .contains("Runner diagnostic is missing")
        );
        engine.cleanup(command.attemptId());
    }

    @Test
    @DisplayName("깨진 진단 파일은 내용 노출 없이 추론 실패와 파싱 오류 로그를 남긴다")
    void transcribe_failure_corruptDiagnostic(CapturedOutput output) throws Exception {
        // given
        failingRunner("pathlib.Path(m['outputDirectory'],'diagnostic.json').write_text('SECRET_TRANSCRIPT{')");
        CreateTranscriptionCommand command = command();
        // when
        TranscriptionException exception = assertThrows(
                TranscriptionException.class,
                () -> engine.transcribe(command)
        );
        // then
        assertEquals(
                "INFERENCE_FAILED",
                exception.getCode()
        );
        assertNotNull(exception.getCause());
        assertTrue(
                output.getOut()
                        .contains("Runner diagnostic could not be parsed")
        );
        assertFalse(
                output.getAll()
                        .contains("SECRET_TRANSCRIPT")
        );
        engine.cleanup(command.attemptId());
    }

    private List<Map<String, Object>> fields() {
        return events.stream()
                .map(this::fields)
                .toList();
    }

    private Map<String, Object> fields(ILoggingEvent event) {
        return event.getKeyValuePairs()
                .stream()
                .collect(
                        Collectors.toMap(
                                pair -> pair.key,
                                pair -> pair.value
                        )
                );
    }

    private void failingRunner(String body) throws Exception {
        Files.writeString(
                pnpm,
                "#!/usr/bin/env python3\nimport sys,pathlib,json\n"
                        + "m=json.loads(pathlib.Path(sys.argv[-1]).read_text())\n" + body + "\nsys.exit(1)\n"
        );
    }

    private void diagnosticRunner(String code) throws Exception {
        Files.writeString(
                pnpm,
                "#!/usr/bin/env python3\nimport sys,pathlib,json\n"
                        + "m=json.loads(pathlib.Path(sys.argv[-1]).read_text())\n"
                        + "pathlib.Path(m['outputDirectory'],'diagnostic.json').write_text(json.dumps({'code':'" + code
                        + "'}))\nsys.exit(1)\n"
        );
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

    private CreateTranscriptionCommand command() {
        return new CreateTranscriptionCommand(
                UUID.randomUUID(),
                "https://test-bucket.s3.ap-northeast-2.amazonaws.com/recordings/audio.webm",
                null
        );
    }
}
