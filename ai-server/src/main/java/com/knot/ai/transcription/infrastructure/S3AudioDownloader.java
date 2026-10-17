package com.knot.ai.transcription.infrastructure;

import com.knot.ai.global.AiServerProperties;
import com.knot.ai.transcription.domain.TranscriptionErrorCode;
import com.knot.ai.transcription.domain.TranscriptionException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class S3AudioDownloader {
    private static final long MAXIMUM_AUDIO_BYTES = 524_288_000L;
    private static final int CONNECT_TIMEOUT_MILLIS = 10_000;
    private static final int READ_TIMEOUT_MILLIS = 30_000;
    private static final int COPY_BUFFER_BYTES = 65_536;

    private final AiServerProperties properties;
    private final ConnectionFactory connections;

    @Autowired
    public S3AudioDownloader(AiServerProperties properties) {
        this(
                properties,
                uri -> (HttpURLConnection) uri.toURL()
                        .openConnection()
        );
    }

    S3AudioDownloader(
            AiServerProperties properties,
            ConnectionFactory connections
    ) {
        this.properties = properties;
        this.connections = connections;
    }

    public URI validate(String downloadUrl) {
        URI uri;
        try {
            uri = URI.create(downloadUrl);
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw new TranscriptionException(
                    TranscriptionErrorCode.INVALID_DOWNLOAD_URL,
                    exception
            );
        }
        if (!"https".equals(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null
                || uri.getFragment() != null || (uri.getPort() != -1 && uri.getPort() != 443)
                || !properties.allowedS3Hosts()
                        .contains(uri.getHost())
                || !uri.getHost()
                        .matches("(?:[a-z0-9.-]+\\.)?s3(?:[.-][a-z0-9-]+)?\\.amazonaws\\.com")) {
            throw new TranscriptionException(TranscriptionErrorCode.INVALID_DOWNLOAD_URL);
        }
        return uri;
    }

    public void download(
            String downloadUrl,
            Path target
    ) throws IOException {
        URI uri = validate(downloadUrl);
        long deadline = System.nanoTime() + properties.downloadTimeout()
                .toNanos();
        checkDeadline(deadline);
        HttpURLConnection connection;
        try {
            connection = connections.open(uri);
        } catch (IOException exception) {
            throw new TranscriptionException(
                    TranscriptionErrorCode.DOWNLOAD_FAILED,
                    exception
            );
        }
        connection.setInstanceFollowRedirects(false);
        connection.setConnectTimeout(CONNECT_TIMEOUT_MILLIS);
        connection.setReadTimeout(READ_TIMEOUT_MILLIS);
        ScheduledExecutorService watchdog = Executors.newSingleThreadScheduledExecutor();
        watchdog.schedule(
                connection::disconnect,
                properties.downloadTimeout()
                        .toNanos(),
                TimeUnit.NANOSECONDS
        );
        try {
            int status = connection.getResponseCode();
            checkDeadline(deadline);
            if (status != 200) {
                throw new TranscriptionException(TranscriptionErrorCode.DOWNLOAD_FAILED);
            }
            long contentLength = connection.getContentLengthLong();
            String contentType = connection.getContentType();
            if ((!"audio/webm".equals(contentType) && !"audio/wav".equals(contentType)) || contentLength <= 0) {
                throw new TranscriptionException(TranscriptionErrorCode.INVALID_AUDIO_METADATA);
            }
            if (contentLength > MAXIMUM_AUDIO_BYTES) {
                throw new TranscriptionException(TranscriptionErrorCode.AUDIO_TOO_LARGE);
            }
            try (InputStream source = connection.getInputStream();
                    OutputStream output = Files.newOutputStream(target)) {
                byte[] buffer = new byte[COPY_BUFFER_BYTES];
                long count = 0;
                int length;
                while ((length = source.read(buffer)) != -1) {
                    checkDeadline(deadline);
                    count += length;
                    if (count > contentLength) {
                        throw new TranscriptionException(TranscriptionErrorCode.AUDIO_TOO_LARGE);
                    }
                    output.write(
                            buffer,
                            0,
                            length
                    );
                }
                checkDeadline(deadline);
                if (count != contentLength) {
                    throw new TranscriptionException(TranscriptionErrorCode.INCOMPLETE_DOWNLOAD);
                }
            }
            if ("audio/wav".equals(contentType)) {
                new AudioInspector().readPcmDuration(
                        target,
                        () -> checkDeadline(deadline)
                );
                checkDeadline(deadline);
            }
        } catch (IOException exception) {
            checkDeadline(deadline);
            throw new TranscriptionException(
                    TranscriptionErrorCode.DOWNLOAD_FAILED,
                    exception
            );
        } finally {
            watchdog.shutdownNow();
            connection.disconnect();
        }
    }

    private void checkDeadline(long deadline) {
        if (Thread.currentThread()
                .isInterrupted()) {
            throw new TranscriptionException(TranscriptionErrorCode.CANCELED);
        }
        if (System.nanoTime() >= deadline) {
            throw new TranscriptionException(TranscriptionErrorCode.DOWNLOAD_TIMEOUT);
        }
    }

    interface ConnectionFactory {

        HttpURLConnection open(URI uri) throws IOException;
    }
}
