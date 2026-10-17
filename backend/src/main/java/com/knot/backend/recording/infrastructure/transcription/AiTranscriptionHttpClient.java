package com.knot.backend.recording.infrastructure.transcription;

import com.knot.backend.global.config.TranscriptionProperties;
import com.knot.backend.recording.application.TranscriptionClient;
import com.knot.backend.recording.application.dto.result.AiTranscriptionJob;
import com.knot.backend.recording.domain.RecordingErrorCode;
import com.knot.backend.recording.domain.RecordingException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

public final class AiTranscriptionHttpClient implements TranscriptionClient {
    private static final String JOB_PATH = "/api/v1/transcription-jobs";
    private static final long MAX_RESPONSE_BYTES = 268_435_456L;

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final TranscriptionProperties transcriptionProperties;

    public AiTranscriptionHttpClient(
            HttpClient httpClient,
            ObjectMapper objectMapper,
            TranscriptionProperties transcriptionProperties
    ) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.transcriptionProperties = transcriptionProperties;
    }

    @Override
    public AiTranscriptionJob submit(
            UUID attemptId,
            String downloadUrl,
            Integer speakerCount
    ) {
        ObjectNode body = objectMapper.createObjectNode();
        if (speakerCount != null) {
            body.put(
                    "speakerCount",
                    speakerCount
            );
        }
        body.put(
                "attemptId",
                attemptId.toString()
        );
        body.put(
                "downloadUrl",
                downloadUrl
        );
        HttpRequest request = request(JOB_PATH).header(
                "Content-Type",
                "application/json"
        )
                .POST(
                        HttpRequest.BodyPublishers.ofString(
                                objectMapper.writeValueAsString(body),
                                StandardCharsets.UTF_8
                        )
                )
                .build();
        return readJob(
                awaitResponse(
                        request,
                        202
                ),
                attemptId
        );
    }

    @Override
    public AiTranscriptionJob get(UUID attemptId) {
        HttpRequest request = request(JOB_PATH + "/" + attemptId).GET()
                .build();
        return readJob(
                awaitResponse(
                        request,
                        200
                ),
                attemptId
        );
    }

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(
                transcriptionProperties.baseUrl()
                        .resolve(path)
        )
                .timeout(transcriptionProperties.requestTimeout())
                .header(
                        "Authorization",
                        "Bearer " + transcriptionProperties.token()
                )
                .header(
                        "Accept",
                        "application/json"
                );
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
                throw httpFailure(
                        response.statusCode(),
                        response.body()
                );
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
            RecordingErrorCode code = exception.getCause() instanceof HttpTimeoutException
                    ? RecordingErrorCode.AI_TIMEOUT
                    : RecordingErrorCode.AI_UNAVAILABLE;
            throw new RecordingException(
                    code,
                    exception
            );
        }
    }

    private RecordingException httpFailure(
            int status,
            String body
    ) {
        RecordingErrorCode code = switch (status) {
            case 401, 403 -> RecordingErrorCode.AI_AUTHENTICATION_FAILED;
            case 404 -> confirmedCode(
                    body,
                    "JOB_NOT_FOUND",
                    RecordingErrorCode.AI_JOB_NOT_FOUND,
                    RecordingErrorCode.AI_INVALID_RESPONSE
            );
            case 409 -> confirmedCode(
                    body,
                    "BUSY",
                    RecordingErrorCode.AI_BUSY,
                    RecordingErrorCode.AI_INVALID_RESPONSE
            );
            case 503 -> confirmedCode(
                    body,
                    "NOT_READY",
                    RecordingErrorCode.AI_NOT_READY,
                    RecordingErrorCode.AI_UNAVAILABLE
            );
            default -> status >= 500 ? RecordingErrorCode.AI_UNAVAILABLE : RecordingErrorCode.AI_INVALID_RESPONSE;
        };
        return new RecordingException(code);
    }

    private RecordingErrorCode confirmedCode(
            String body,
            String expected,
            RecordingErrorCode confirmed,
            RecordingErrorCode ambiguous
    ) {
        try {
            JsonNode error = objectMapper.readTree(body);
            if (error != null && expected.equals(
                    error.path("code")
                            .asString()
            )) {
                return confirmed;
            }
            return ambiguous;
        } catch (JacksonException exception) {
            return ambiguous;
        }
    }

    private AiTranscriptionJob readJob(
            String body,
            UUID expectedId
    ) {
        try {
            AiTranscriptionJob job = objectMapper.readValue(
                    body,
                    AiTranscriptionJob.class
            );
            if (job == null || !expectedId.equals(job.attemptId()) || job.status() == null) {
                throw new RecordingException(RecordingErrorCode.AI_INVALID_RESPONSE);
            }
            boolean valid = switch (job.status()) {
                case PROCESSING -> job.result() == null && job.failureCode() == null;
                case SUCCEEDED -> job.result() != null && job.failureCode() == null;
                case FAILED -> job.result() == null && job.failureCode() != null && job.failureCode()
                        .matches("[A-Z][A-Z0-9_]{0,99}");
            };
            if (!valid) {
                throw new RecordingException(RecordingErrorCode.AI_INVALID_RESPONSE);
            }
            return job;
        } catch (JacksonException exception) {
            throw new RecordingException(
                    RecordingErrorCode.AI_INVALID_RESPONSE,
                    exception
            );
        }
    }
}
