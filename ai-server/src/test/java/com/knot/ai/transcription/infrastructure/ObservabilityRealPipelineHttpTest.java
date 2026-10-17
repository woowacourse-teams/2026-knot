package com.knot.ai.transcription.infrastructure;

import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import com.knot.ai.KnotAiApplication;
import com.knot.ai.global.AiServerProperties;
import com.knot.ai.transcription.application.TranscriptionJobService;
import com.knot.ai.transcription.application.TranscriptionLog;
import com.sun.net.httpserver.HttpServer;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.slf4j.LoggerFactory;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import tools.jackson.databind.json.JsonMapper;

@EnabledIfEnvironmentVariable(named = "AI_REAL_OBSERVABILITY", matches = "true")
class ObservabilityRealPipelineHttpTest {
    private static final Set<String> FIELDS = Set.of(
            "event",
            "outcome",
            "attemptId",
            "elapsedMs",
            "errorCode"
    );
    private static HttpServer storage;

    @Test
    @DisplayName("분리된 실제 AI에서 백엔드 정상·변환 실패·저장 실패 시나리오의 지표와 로그를 관측한다")
    void observe_realModelAndExternalBackend() throws Exception {
        // given
        Path evidence = Path.of(System.getenv("AI_OBSERVABILITY_EVIDENCE_DIR"));
        Files.createDirectories(evidence);
        Files.setPosixFilePermissions(
                evidence,
                PosixFilePermissions.fromString("rwx------")
        );
        Path work = Files.createTempDirectory("knot-ai-observability-work-");
        byte[] audio = Files.readAllBytes(Path.of(System.getenv("AI_REAL_AUDIO")));
        storage = HttpServer.create(
                new InetSocketAddress(
                        "127.0.0.1",
                        0
                ),
                0
        );
        storage.createContext(
                "/recordings/",
                exchange -> {
                    byte[] body = fixtureAudio(
                            exchange.getRequestURI()
                                    .getPath(),
                            audio
                    );
                    exchange.getResponseHeaders()
                            .add(
                                    "Content-Type",
                                    "audio/webm"
                            );
                    exchange.sendResponseHeaders(
                            200,
                            body.length
                    );
                    exchange.getResponseBody()
                            .write(body);
                    exchange.close();
                }
        );
        storage.start();
        List<ILoggingEvent> events = new CopyOnWriteArrayList<>();
        AppenderBase<ILoggingEvent> appender = new AppenderBase<>() {
            @Override
            protected void append(ILoggingEvent event) {
                events.add(event);
            }
        };
        ConfigurableApplicationContext context = null;
        Logger logger = null;
        try {
            context = new SpringApplicationBuilder(
                    KnotAiApplication.class,
                    StorageConfiguration.class
            ).run(
                    "--server.address=127.0.0.1",
                    "--server.port=" + System.getenv()
                            .getOrDefault(
                                    "AI_OBSERVABILITY_PORT",
                                    "19765"
                            ),
                    "--ai.token=" + System.getenv("AI_OBSERVABILITY_TOKEN"),
                    "--ai.work-directory=" + work,
                    "--ai.profile-file=" + System.getenv("AI_REAL_PROFILE"),
                    "--ai.runner-directory=" + Path.of("runner")
                            .toAbsolutePath(),
                    "--ai.allowed-s3-hosts=test-bucket.s3.ap-northeast-2.amazonaws.com",
                    "--ai.inference-timeout=10m",
                    "--logging.level.root=WARN",
                    "--logging.level.com.knot.ai.transcription.application.TranscriptionLog=INFO",
                    "--spring.main.banner-mode=off"
            );
            logger = (Logger) LoggerFactory.getLogger(TranscriptionLog.class);
            appender.start();
            logger.addAppender(appender);
            privateWrite(
                    evidence.resolve("ready.json"),
                    "{\"startedAt\":\"" + Instant.now() + "\"}"
            );

            // when
            awaitBackend(evidence.resolve("stop"));

            // then
            String metrics = context.getBean(TranscriptionJobService.class)
                    .getMetrics();
            assertTrue(metrics.contains("knot_ai_jobs_completed_total{outcome=\"completed\"} 2"));
            assertTrue(metrics.contains("knot_ai_jobs_completed_total{outcome=\"failed\"} 1"));
            assertTrue(metrics.contains("stage=\"decode\",outcome=\"failed\"} 1"));
            privateWrite(
                    evidence.resolve("ai-metrics.txt"),
                    metrics
            );
        } finally {
            List<Map<String, Object>> approved = new ArrayList<>();
            for (ILoggingEvent event : events) {
                if (event.getKeyValuePairs() == null) {
                    continue;
                }
                Map<String, Object> fields = new java.util.LinkedHashMap<>();
                event.getKeyValuePairs()
                        .stream()
                        .filter(pair -> FIELDS.contains(pair.key))
                        .forEach(
                                pair -> fields.put(
                                        pair.key,
                                        pair.value
                                )
                        );
                if (!fields.isEmpty()) {
                    fields.put(
                            "timestamp",
                            event.getTimeStamp()
                    );
                    approved.add(fields);
                }
            }
            privateWrite(
                    evidence.resolve("ai-events.json"),
                    JsonMapper.builder()
                            .build()
                            .writeValueAsString(approved)
            );
            if (logger != null) {
                logger.detachAppender(appender);
            }
            appender.stop();
            if (context != null) {
                context.close();
            }
            storage.stop(0);
            try (java.util.stream.Stream<Path> files = Files.walk(work)) {
                for (Path path : files.sorted(Comparator.reverseOrder())
                        .toList()) {
                    Files.delete(path);
                }
            }
        }
    }

    private byte[] fixtureAudio(
            String path,
            byte[] audio
    ) {
        if (path.endsWith("broken.webm")) {
            return "invalid-webm".getBytes(StandardCharsets.UTF_8);
        }
        return audio;
    }

    private void awaitBackend(Path stop) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofMinutes(10)
                .toNanos();
        while (!Files.exists(stop) && System.nanoTime() < deadline) {
            Thread.sleep(200);
        }
        assertTrue(
                Files.exists(stop),
                "백엔드의 실제 관측 시나리오가 제한 시간 내 완료되지 않았습니다"
        );
    }

    private void privateWrite(
            Path target,
            String contents
    ) throws Exception {
        if (!Files.exists(target)) {
            Files.createFile(
                    target,
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))
            );
        }
        Files.writeString(
                target,
                contents
        );
    }

    @org.springframework.boot.test.context.TestConfiguration(proxyBeanMethods = false)
    static class StorageConfiguration {
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
