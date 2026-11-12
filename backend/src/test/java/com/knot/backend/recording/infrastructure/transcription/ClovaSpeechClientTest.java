package com.knot.backend.recording.infrastructure.transcription;
import static org.assertj.core.api.Assertions.*;
import com.knot.backend.global.config.ClovaSpeechProperties;
import com.knot.backend.global.config.TranscriptionProperties;
import com.knot.backend.recording.application.dto.result.AiTranscriptionJob;
import com.knot.backend.recording.domain.RecordingException;
import com.knot.backend.testsupport.LlmHttpServer;
import java.net.http.HttpClient;
import java.time.Duration;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.ObjectMapper;
class ClovaSpeechClientTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private ClovaSpeechClient client(LlmHttpServer server) {
        ClovaSpeechProperties p = new ClovaSpeechProperties();
        p.setInvokeUrl(server.baseUrl());
        p.setApiKey("test-secret");
        return new ClovaSpeechClient(
                HttpClient.newHttpClient(),
                mapper,
                p,
                new TranscriptionProperties(
                        true,
                        null,
                        null,
                        Duration.ofSeconds(1),
                        Duration.ofSeconds(2),
                        Duration.ofSeconds(1),
                        Duration.ofSeconds(10)
                )
        );
    }
    private void respond(
            LlmHttpServer server,
            String response
    ) {
        server.respond(
                "/recognizer/url",
                exchange -> {
                    assertThat(
                            exchange.getRequestHeaders()
                                    .getFirst("X-CLOVASPEECH-API-KEY")
                    ).isEqualTo("test-secret");
                    byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(
                            200,
                            bytes.length
                    );
                    exchange.getResponseBody()
                            .write(bytes);
                }
        );
    }
    @Test
    void countAndSpeakerMappingAndLostResponseDoesNotResubmit() throws Exception {
        try (LlmHttpServer server = new LlmHttpServer()) {
            respond(
                    server,
                    "{\"result\":\"COMPLETED\",\"text\":\"안녕\",\"segments\":[{\"start\":100,\"end\":900,\"text\":\"안녕\",\"speaker\":{\"label\":\"2\"}}]}"
            );
            var client = client(server);
            var result = client.submit(
                    UUID.randomUUID(),
                    "https://audio.example/test",
                    3
            );
            assertThat(result.status()).isEqualTo(AiTranscriptionJob.Status.SUCCEEDED);
            assertThat(
                    result.result()
                            .toUtterances()
            ).hasSize(1);
            var body = mapper.readTree(server.requestBody());
            assertThat(
                    body.path("diarization")
                            .path("speakerCountMin")
                            .asInt()
            ).isEqualTo(3);
            assertThat(
                    body.path("diarization")
                            .path("speakerCountMax")
                            .asInt()
            ).isEqualTo(3);
            assertThat(
                    client.get(UUID.randomUUID())
                            .status()
            ).isEqualTo(AiTranscriptionJob.Status.FAILED);
            assertThat(server.requestCount()).isEqualTo(1);
        }
    }
    @Test
    void acceptsOnlySuccessfulEmptyAndOmitsAutomaticCount() throws Exception {
        try (LlmHttpServer server = new LlmHttpServer()) {
            respond(
                    server,
                    "{\"result\":\"COMPLETED\",\"text\":\"\",\"segments\":[]}"
            );
            assertThat(
                    client(server).submit(
                            UUID.randomUUID(),
                            "https://audio.example/test",
                            null
                    )
                            .result()
                            .toUtterances()
            ).isEmpty();
            assertThat(
                    mapper.readTree(server.requestBody())
                            .path("diarization")
                            .has("speakerCountMin")
            ).isFalse();
        }
    }
    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"result\":\"ERROR\",\"text\":\"\",\"segments\":[]}",
            "{\"result\":\"COMPLETED\",\"text\":\"발화 누락\",\"segments\":[]}"})
    void malformedIsNotEmpty(String response) throws Exception {
        try (LlmHttpServer server = new LlmHttpServer()) {
            respond(
                    server,
                    response
            );
            assertThatThrownBy(
                    () -> client(server).submit(
                            UUID.randomUUID(),
                            "https://audio.example/test",
                            null
                    )
            ).isInstanceOf(RecordingException.class);
        }
    }
}
