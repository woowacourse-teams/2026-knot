package com.knot.ai.transcription.infrastructure;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import com.sun.net.httpserver.HttpServer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import com.knot.ai.KnotAiApplication;
import com.knot.ai.global.AiServerProperties;
@EnabledIfEnvironmentVariable(named = "AI_REAL_PIPELINE", matches = "true")
class RealPipelineHttpTest {
    private static HttpServer storage;
    @Test
    @DisplayName("실제 한국어 녹음을 HTTP API와 설치된 모델로 전사한다")
    void transcribe_success_realKoreanHttpPipeline() throws Exception {
        // given
        Path audio = Path.of(System.getenv("AI_REAL_AUDIO"));
        // when
        JsonNode result = executeHttpPipeline(
                audio,
                false
        );
        // then
        assertEquals(
                "SUCCEEDED",
                result.path("status")
                        .asString()
        );
        assertTrue(
                result.path("result")
                        .path("window")
                        .isNull()
                        || result.path("result")
                                .path("window")
                                .isMissingNode()
        );
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "AI_REAL_WINDOW_AUDIO", matches = ".+")
    @DisplayName("실제 WAV 구간을 HTTP로 접수하고 단어 시간과 실제 화자 임베딩을 받는다")
    void transcribe_success_realWindowHttpPipeline() throws Exception {
        // given
        Path audio = Path.of(System.getenv("AI_REAL_WINDOW_AUDIO"));
        // when
        JsonNode result = executeHttpPipeline(
                audio,
                true
        );
        // then
        assertEquals(
                "SUCCEEDED",
                result.path("status")
                        .asString()
        );
        assertTrue(
                result.path("result")
                        .path("window")
                        .path("tokens")
                        .size() > 0
        );
    }

    @Test
    @DisplayName("실제 변환 준비 HTTP는 모델 실행 없이 canonical WAV와 체크섬을 제공한다")
    void prepare_success_realCanonicalHttp() throws Exception {
        // given
        Path work = Files.createTempDirectory("knot-ai-real-prepare-");
        Path preparationRoot = work.resolveSibling(work.getFileName() + "-preparations");
        prepareStorage(Files.readAllBytes(Path.of(System.getenv("AI_REAL_AUDIO"))));
        Map<String, Object> configuration = settings(
                work,
                Path.of(System.getenv("AI_REAL_PROFILE")),
                Path.of("runner")
                        .toAbsolutePath()
        );
        ConfigurableApplicationContext context = new SpringApplicationBuilder(
                KnotAiApplication.class,
                TestConfiguration.class
        ).run(
                configuration.entrySet()
                        .stream()
                        .map(entry -> "--" + entry.getKey() + "=" + entry.getValue())
                        .toArray(String[]::new)
        );
        int port = ((ServletWebServerApplicationContext) context).getWebServer()
                .getPort();
        URI origin = URI.create("http://127.0.0.1:" + port);
        UUID id = UUID.randomUUID();
        JsonMapper mapper = JsonMapper.builder()
                .findAndAddModules()
                .build();
        HttpClient client = HttpClient.newHttpClient();
        String body = mapper.writeValueAsString(
                Map.of(
                        "attemptId",
                        id,
                        "downloadUrl",
                        "https://test-bucket.s3.ap-northeast-2.amazonaws.com/recordings/audio.webm?fixture=1"
                )
        );
        try {
            // when
            HttpResponse<String> accepted = client.send(
                    request(origin.resolve("/api/v1/audio-preparations")).header(
                            "Content-Type",
                            "application/json"
                    )
                            .POST(HttpRequest.BodyPublishers.ofString(body))
                            .build(),
                    HttpResponse.BodyHandlers.ofString()
            );
            assertEquals(
                    202,
                    accepted.statusCode()
            );
            JsonNode snapshot = null;
            long deadline = System.nanoTime() + Duration.ofSeconds(30)
                    .toNanos();
            while (System.nanoTime() < deadline) {
                HttpResponse<String> response = client.send(
                        request(origin.resolve("/api/v1/audio-preparations/" + id)).GET()
                                .build(),
                        HttpResponse.BodyHandlers.ofString()
                );
                assertEquals(
                        200,
                        response.statusCode()
                );
                snapshot = mapper.readTree(response.body());
                if (!"PROCESSING".equals(
                        snapshot.path("status")
                                .asString()
                )) {
                    break;
                }
                Thread.sleep(20);
            }
            // then
            assertEquals(
                    "SUCCEEDED",
                    snapshot.path("status")
                            .asString()
            );
            HttpResponse<byte[]> audio = client.send(
                    request(origin.resolve("/api/v1/audio-preparations/" + id + "/audio")).GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofByteArray()
            );
            assertEquals(
                    200,
                    audio.statusCode()
            );
            assertEquals(
                    "audio/wav",
                    audio.headers()
                            .firstValue("Content-Type")
                            .orElse("")
            );
            JsonNode result = snapshot.path("result");
            assertEquals(
                    "pcm16le-16k-mono-v1",
                    result.path("normalizationVersion")
                            .asString()
            );
            assertEquals(
                    result.path("byteLength")
                            .asLong(),
                    audio.body().length
            );
            assertEquals(
                    44 + result.path("totalFrames")
                            .asLong() * 2,
                    audio.body().length
            );
            assertEquals(
                    result.path("checksumSha256")
                            .asString(),
                    java.util.Base64.getEncoder()
                            .encodeToString(
                                    java.security.MessageDigest.getInstance("SHA-256")
                                            .digest(audio.body())
                            )
            );
            java.nio.ByteBuffer wav = java.nio.ByteBuffer.wrap(audio.body())
                    .order(java.nio.ByteOrder.LITTLE_ENDIAN);
            assertEquals(
                    16,
                    wav.getInt(16)
            );
            assertEquals(
                    16000,
                    wav.getInt(24)
            );
            assertEquals(
                    audio.body().length - 44,
                    wav.getInt(40)
            );
            HttpResponse<String> replay = client.send(
                    request(origin.resolve("/api/v1/audio-preparations")).header(
                            "Content-Type",
                            "application/json"
                    )
                            .POST(HttpRequest.BodyPublishers.ofString(body))
                            .build(),
                    HttpResponse.BodyHandlers.ofString()
            );
            assertEquals(
                    "SUCCEEDED",
                    mapper.readTree(replay.body())
                            .path("status")
                            .asString()
            );
            HttpResponse<String> forbidden = client.send(
                    HttpRequest.newBuilder(origin.resolve("/api/v1/audio-preparations/" + id + "/audio"))
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofString()
            );
            assertEquals(
                    401,
                    forbidden.statusCode()
            );
        } finally {
            context.close();
            storage.stop(0);
            for (Path directory : java.util.List.of(
                    work,
                    preparationRoot
            )) {
                if (Files.exists(directory)) {
                    try (java.util.stream.Stream<Path> files = Files.walk(directory)) {
                        for (Path file : files.sorted(Comparator.reverseOrder())
                                .toList()) {
                            Files.delete(file);
                        }
                    }
                }
            }
        }
    }

    private JsonNode executeHttpPipeline(
            Path audio,
            boolean windowMode
    ) throws Exception {
        // given
        Path profile = Path.of(System.getenv("AI_REAL_PROFILE"));
        Path runner = Path.of("runner")
                .toAbsolutePath()
                .normalize();
        Path work = Files.createTempDirectory("knot-ai-real-http-");
        byte[] bytes = Files.readAllBytes(audio);
        prepareStorage(bytes);
        Map<String, Object> settings = settings(
                work,
                profile,
                runner
        );
        ConfigurableApplicationContext context = new SpringApplicationBuilder(
                KnotAiApplication.class,
                TestConfiguration.class
        ).run(
                settings.entrySet()
                        .stream()
                        .map(entry -> "--" + entry.getKey() + "=" + entry.getValue())
                        .toArray(String[]::new)
        );
        int port = ((ServletWebServerApplicationContext) context).getWebServer()
                .getPort();
        URI origin = URI.create("http://127.0.0.1:" + port);
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        JsonMapper mapper = JsonMapper.builder()
                .findAndAddModules()
                .build();
        UUID attemptId = UUID.randomUUID();
        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put(
                "attemptId",
                attemptId
        );
        requestBody.put(
                "downloadUrl",
                "https://test-bucket.s3.ap-northeast-2.amazonaws.com/recordings/audio.webm?fixture=1"
        );
        long frames = windowMode ? Math.round(new AudioInspector().readPcmDuration(audio) * 16000) : 0;
        String manifestHash = "a".repeat(64);
        if (windowMode) {
            Map<String, Object> window = new HashMap<>(
                    Map.of(
                            "pipelineVersion",
                            "pcm-http-test-v1",
                            "inputManifestSha256",
                            manifestHash,
                            "inputStartFrame",
                            16000,
                            "inputEndFrame",
                            16000 + frames
                    )
            );
            String decodePolicy = System.getenv("AI_REAL_WINDOW_DECODE_POLICY");
            if (decodePolicy != null) {
                window.put(
                        "decodePolicy",
                        decodePolicy
                );
            }
            requestBody.put(
                    "window",
                    window
            );
        }
        String body = mapper.writeValueAsString(requestBody);
        try {
            // when
            JsonNode result = submitAndPoll(
                    client,
                    origin,
                    mapper,
                    attemptId,
                    body
            );
            // then
            assertEquals(
                    "SUCCEEDED",
                    result.path("status")
                            .asString()
            );
            JsonNode transcript = result.path("result");
            assertEquals(
                    1,
                    transcript.path("schemaVersion")
                            .asInt()
            );
            assertTrue(
                    mapper.readTree(
                            transcript.path("rawWhisperJson")
                                    .asString()
                    )
                            .isObject()
            );
            assertTrue(
                    transcript.path("utterances")
                            .isArray()
            );
            assertTrue(
                    transcript.path("utterances")
                            .size() > 0
            );
            assertTrue(
                    transcript.path("utterances")
                            .get(0)
                            .path("text")
                            .asString()
                            .matches("(?s).*[가-힣].*")
            );
            if (windowMode) {
                JsonNode evidence = transcript.path("window");
                assertEquals(
                        1,
                        evidence.path("schemaVersion")
                                .asInt()
                );
                assertEquals(
                        "pcm-http-test-v1",
                        evidence.path("pipelineVersion")
                                .asString()
                );
                assertEquals(
                        manifestHash,
                        evidence.path("inputManifestSha256")
                                .asString()
                );
                assertEquals(
                        16000,
                        evidence.path("inputStartFrame")
                                .asLong()
                );
                assertEquals(
                        16000 + frames,
                        evidence.path("inputEndFrame")
                                .asLong()
                );
                assertTrue(
                        evidence.path("modelVersion")
                                .asString()
                                .startsWith("1.13.6:")
                );
                assertTrue(
                        evidence.path("tokens")
                                .size() > 0
                );
                for (JsonNode token : evidence.path("tokens")) {
                    assertTrue(
                            token.path("startFrame")
                                    .asLong() >= 0
                    );
                    assertTrue(
                            token.path("endFrame")
                                    .asLong() <= frames
                    );
                    assertTrue(
                            token.path("endFrame")
                                    .asLong() > token.path("startFrame")
                                            .asLong()
                    );
                    assertTrue(
                            !token.path("text")
                                    .asString()
                                    .isBlank()
                    );
                }
                boolean actualEmbedding = false;
                for (JsonNode speaker : evidence.path("speakers")) {
                    if (speaker.path("embedding")
                            .size() == 512) {
                        actualEmbedding = true;
                        for (JsonNode value : speaker.path("embedding")) {
                            assertTrue(Double.isFinite(value.asDouble()));
                        }
                    }
                }
                assertTrue(actualEmbedding);
                try (java.util.stream.Stream<Path> files = Files.list(work)) {
                    assertEquals(
                            0,
                            files.count()
                    );
                }
            } else {
                assertTrue(
                        transcript.path("window")
                                .isMissingNode()
                                || transcript.path("window")
                                        .isNull()
                );
            }
            return result;
        } finally {
            context.close();
            storage.stop(0);
            try (java.util.stream.Stream<Path> files = Files.walk(work)) {
                for (Path file : files.sorted(Comparator.reverseOrder())
                        .toList()) {
                    Files.delete(file);
                }
            }
        }
    }
    private void prepareStorage(byte[] bytes) throws Exception {
        storage = HttpServer.create(
                new InetSocketAddress(
                        "127.0.0.1",
                        0
                ),
                0
        );
        storage.createContext(
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
                                    "\"fixture\""
                            );
                    exchange.sendResponseHeaders(
                            200,
                            bytes.length
                    );
                    exchange.getResponseBody()
                            .write(bytes);
                    exchange.close();
                }
        );
        storage.start();
    }

    private JsonNode submitAndPoll(
            HttpClient client,
            URI origin,
            JsonMapper mapper,
            UUID attemptId,
            String body
    ) throws Exception {
        HttpResponse<String> accepted = client.send(
                request(origin.resolve("/api/v1/transcription-jobs")).header(
                        "Content-Type",
                        "application/json"
                )
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build(),
                HttpResponse.BodyHandlers.ofString()
        );
        assertEquals(
                202,
                accepted.statusCode()
        );
        long deadline = System.nanoTime() + Duration.ofMinutes(10)
                .toNanos();
        while (System.nanoTime() < deadline) {
            HttpResponse<String> response = client.send(
                    request(origin.resolve("/api/v1/transcription-jobs/" + attemptId)).GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofString()
            );
            assertEquals(
                    200,
                    response.statusCode()
            );
            JsonNode result = mapper.readTree(response.body());
            if (!result.path("status")
                    .asString()
                    .equals("PROCESSING")) {
                return result;
            }
            Thread.sleep(1000);
        }
        throw new AssertionError("실제 모델 실행이 제한 시간 안에 완료되지 않았습니다");
    }
    private HttpRequest.Builder request(URI uri) {
        return HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(15))
                .header(
                        "Authorization",
                        "Bearer test-token"
                );
    }
    private Map<String, Object> settings(
            Path work,
            Path profile,
            Path runner
    ) {
        Map<String, Object> settings = new HashMap<>();
        settings.put(
                "server.address",
                "127.0.0.1"
        );
        settings.put(
                "server.port",
                0
        );
        settings.put(
                "ai.token",
                "test-token"
        );
        settings.put(
                "ai.work-directory",
                work.toString()
        );
        settings.put(
                "ai.profile-file",
                profile.toString()
        );
        settings.put(
                "ai.runner-directory",
                runner.toString()
        );
        settings.put(
                "ai.allowed-s3-hosts",
                "test-bucket.s3.ap-northeast-2.amazonaws.com"
        );
        settings.put(
                "ai.inference-timeout",
                "10m"
        );
        settings.put(
                "logging.level.root",
                "WARN"
        );
        settings.put(
                "spring.main.banner-mode",
                "off"
        );
        return settings;
    }
    @org.springframework.boot.test.context.TestConfiguration(proxyBeanMethods = false)
    static class TestConfiguration {
        @Bean
        @Primary
        S3AudioDownloader testDownloader(AiServerProperties properties) {
            return new S3AudioDownloader(
                    properties,
                    uri -> (HttpURLConnection) URI.create(
                            "http://127.0.0.1:" + storage.getAddress()
                                    .getPort() + uri.getPath()
                    )
                            .toURL()
                            .openConnection()
            );
        }
    }
}
