package com.knot.backend.recording.infrastructure.transcription;

import com.knot.backend.global.config.ClovaSpeechProperties;
import com.knot.backend.global.config.TranscriptionProperties;
import com.knot.backend.recording.application.TranscriptionClient;
import com.knot.backend.recording.application.dto.result.AiTranscript;
import com.knot.backend.recording.application.dto.result.AiTranscriptionJob;
import com.knot.backend.recording.domain.RecordingErrorCode;
import com.knot.backend.recording.domain.RecordingException;
import com.knot.backend.recording.domain.model.RecordingSession;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Temporary whole-file provider. A lost sync response cannot safely be
 * re-POSTed.
 */
public final class ClovaSpeechClient implements TranscriptionClient {
    private static final long MAX_RESPONSE_BYTES = 16_777_216;
    private final HttpClient httpClient;
    private final ObjectMapper mapper;
    private final ClovaSpeechProperties properties;
    private final TranscriptionProperties transcriptionProperties;

    public ClovaSpeechClient(
            HttpClient client,
            ObjectMapper mapper,
            ClovaSpeechProperties properties,
            TranscriptionProperties worker
    ) {
        this.httpClient = client;
        this.mapper = mapper.rebuild()
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .build();
        this.properties = properties;
        this.transcriptionProperties = worker;
    }

    @Override
    public AiTranscriptionJob submit(
            UUID attemptId,
            String downloadUrl,
            Integer speakerCount
    ) {
        if (speakerCount != null && (speakerCount < 1 || speakerCount > 10)) {
            throw new RecordingException(RecordingErrorCode.INVALID_RECORDING_DATA);
        }
        ObjectNode body = mapper.createObjectNode();
        body.put(
                "url",
                downloadUrl
        )
                .put(
                        "language",
                        "ko-KR"
                )
                .put(
                        "completion",
                        "sync"
                )
                .put(
                        "wordAlignment",
                        false
                )
                .put(
                        "fullText",
                        true
                );
        ObjectNode diarization = body.putObject("diarization");
        diarization.put(
                "enable",
                true
        );
        if (speakerCount != null) {
            diarization.put(
                    "speakerCountMin",
                    speakerCount
            )
                    .put(
                            "speakerCountMax",
                            speakerCount
                    );
        }
        URI uri = URI.create(
                properties.getInvokeUrl()
                        .toString()
                        .replaceAll(
                                "/$",
                                ""
                        ) + "/recognizer/url"
        );
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(transcriptionProperties.requestTimeout())
                .header(
                        "X-CLOVASPEECH-API-KEY",
                        properties.getApiKey()
                )
                .header(
                        "Content-Type",
                        "application/json"
                )
                .POST(
                        HttpRequest.BodyPublishers.ofString(
                                mapper.writeValueAsString(body),
                                StandardCharsets.UTF_8
                        )
                )
                .build();
        return new AiTranscriptionJob(
                attemptId,
                AiTranscriptionJob.Status.SUCCEEDED,
                null,
                readTranscript(
                        awaitResponse(
                                request,
                                200
                        )
                )
        );
    }

    @Override
    public AiTranscriptionJob get(UUID attemptId) {
        // NOT_FOUND would instruct the existing worker to submit again and could
        // duplicate a paid call.
        return new AiTranscriptionJob(
                attemptId,
                AiTranscriptionJob.Status.FAILED,
                "AI_OUTCOME_UNKNOWN",
                null
        );
    }

    private AiTranscript readTranscript(String body) {
        try {
            JsonNode root = mapper.readTree(body);
            if (root == null || !"COMPLETED".equals(
                    root.path("result")
                            .asString()
            ) || !root.path("segments")
                    .isArray() || !root.path("text")
                            .isString()) {
                throw invalid();
            }
            List<AiTranscript.Utterance> utterances = new ArrayList<>();
            ObjectNode raw = mapper.createObjectNode();
            var transcription = raw.putArray("transcription");
            long previousStart = 0;
            for (JsonNode segment : root.path("segments")) {
                validateSegment(
                        segment,
                        previousStart
                );
                previousStart = segment.path("start")
                        .asLong();
                String text = segment.path("text")
                        .asString();
                if (blank(text)) {
                    continue;
                }
                String speaker = null;
                JsonNode label = segment.path("speaker")
                        .path("label");
                if (label.isString() && !label.asString()
                        .isBlank()) {
                    speaker = "CLOVA_" + label.asString();
                }
                utterances.add(
                        new AiTranscript.Utterance(
                                utterances.size(),
                                speaker,
                                previousStart / 1000.0,
                                segment.path("end")
                                        .asLong() / 1000.0,
                                text
                        )
                );
                transcription.addObject()
                        .put(
                                "text",
                                text
                        );
            }
            if (utterances.isEmpty() && !blank(
                    root.path("text")
                            .asString()
            )) {
                throw invalid();
            }
            return new AiTranscript(
                    1,
                    mapper.writeValueAsString(raw),
                    utterances
            );
        } catch (JacksonException exception) {
            throw new RecordingException(
                    RecordingErrorCode.AI_INVALID_RESPONSE,
                    exception
            );
        }
    }

    private void validateSegment(
            JsonNode segment,
            long previousStart
    ) {
        JsonNode start = segment.path("start");
        JsonNode end = segment.path("end");
        if (!start.isIntegralNumber() || !end.isIntegralNumber() || !start.canConvertToLong() || !end.canConvertToLong()
                || start.asLong() < previousStart || end.asLong() < start.asLong()
                || end.asLong() > RecordingSession.MAX_RECORDING_MILLIS || !segment.path("text")
                        .isString()) {
            throw invalid();
        }
    }

    private boolean blank(String text) {
        return text.codePoints()
                .noneMatch(c -> !Character.isWhitespace(c) && !Character.isSpaceChar(c));
    }

    private RecordingException invalid() {
        return new RecordingException(RecordingErrorCode.AI_INVALID_RESPONSE);
    }

    private RecordingException httpFailure(int status) {
        if (status == 401 || status == 403) {
            return new RecordingException(RecordingErrorCode.AI_AUTHENTICATION_FAILED);
        }
        if (status == 429) {
            return new RecordingException(RecordingErrorCode.AI_BUSY);
        }
        if (status >= 500) {
            return new RecordingException(RecordingErrorCode.AI_UNAVAILABLE);
        }
        return invalid();
    }

    private String awaitResponse(
            HttpRequest request,
            int expectedStatus
    ) {
        CompletableFuture<HttpResponse<String>> future = httpClient.sendAsync(
                request,
                HttpResponse.BodyHandlers.limiting(
                        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8),
                        MAX_RESPONSE_BYTES
                )
        );
        try {
            HttpResponse<String> response = future.get(
                    transcriptionProperties.requestTimeout()
                            .toMillis(),
                    TimeUnit.MILLISECONDS
            );
            if (response.statusCode() != expectedStatus) {
                throw httpFailure(response.statusCode());
            }
            return response.body();
        } catch (TimeoutException exception) {
            future.cancel(true);
            throw new RecordingException(
                    RecordingErrorCode.AI_TIMEOUT,
                    exception
            );
        } catch (InterruptedException exception) {
            future.cancel(true);
            Thread.currentThread()
                    .interrupt();
            throw new RecordingException(
                    RecordingErrorCode.AI_INTERRUPTED,
                    exception
            );
        } catch (ExecutionException exception) {
            RecordingErrorCode code = RecordingErrorCode.AI_UNAVAILABLE;
            if (exception.getCause() instanceof HttpTimeoutException) {
                code = RecordingErrorCode.AI_TIMEOUT;
            }
            throw new RecordingException(
                    code,
                    exception
            );
        }
    }

}
