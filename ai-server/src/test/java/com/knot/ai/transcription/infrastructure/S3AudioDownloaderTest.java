package com.knot.ai.transcription.infrastructure;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.knot.ai.TestFixtures;
import com.knot.ai.transcription.domain.TranscriptionException;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

class S3AudioDownloaderTest {
    private static final String DOWNLOAD_URL = "https://test-bucket.s3.ap-northeast-2.amazonaws.com/a?sig=test";

    @TempDir
    Path directory;

    @Test
    @DisplayName("서명 URL로 오디오를 내려받고 연결을 닫는다")
    void download_success() throws Exception {
        // given
        StubConnection connection = new StubConnection();
        Path target = directory.resolve("input.webm");
        S3AudioDownloader downloader = downloader(connection);

        // when
        downloader.download(
                DOWNLOAD_URL,
                target
        );

        // then
        assertArrayEquals(
                new byte[]{1, 2, 3},
                Files.readAllBytes(target)
        );
        assertTrue(connection.disconnected.getCount() == 0);
        assertFalse(connection.getInstanceFollowRedirects());
    }

    @ParameterizedTest
    @ValueSource(strings = {"canonical", "metadataBeforeData", "metadataAfterData"})
    @DisplayName("PCM16 mono 16kHz WAV의 실제 바이트와 추가 chunk를 검증하고 저장한다")
    void download_success_pcmWav(String layout) throws Exception {
        // given
        StubConnection connection = new StubConnection();
        connection.contentType = "audio/wav";
        connection.body = pcmWav(layout);
        connection.contentLength = connection.body.length;
        Path target = directory.resolve("input.wav");
        S3AudioDownloader downloader = downloader(connection);

        // when
        downloader.download(
                DOWNLOAD_URL,
                target
        );

        // then
        assertArrayEquals(
                connection.body,
                Files.readAllBytes(target)
        );
        assertTrue(connection.disconnected.getCount() == 0);
    }

    @ParameterizedTest
    @ValueSource(strings = {"mimeSpoof", "truncated", "riffLength", "sampleRate", "channels", "floatEncoding",
            "sampleWidth", "unalignedData", "trailingBytes", "truncatedChunk", "missingData"})
    @DisplayName("WAV MIME으로 선언해도 손상된 컨테이너와 잘못된 PCM 형식은 거부한다")
    void download_failure_invalidWav(String defect) throws Exception {
        // given
        StubConnection connection = new StubConnection();
        connection.contentType = "audio/wav";
        connection.body = invalidWav(defect);
        connection.contentLength = connection.body.length;
        S3AudioDownloader downloader = downloader(connection);

        // when
        TranscriptionException exception = assertThrows(
                TranscriptionException.class,
                () -> downloader.download(
                        DOWNLOAD_URL,
                        directory.resolve("input.wav")
                )
        );

        // then
        assertEquals(
                "INVALID_AUDIO_FORMAT",
                exception.getCode()
        );
        assertTrue(connection.disconnected.getCount() == 0);
    }

    @Test
    @DisplayName("연결 생성 오류는 다운로드 실패로 분류하고 원인을 보존한다")
    void download_failure_connectionOpening() {
        // given
        IOException cause = new IOException("fixture");
        S3AudioDownloader downloader = new S3AudioDownloader(
                TestFixtures.properties(directory),
                uri -> {
                    throw cause;
                }
        );

        // when
        TranscriptionException exception = assertThrows(
                TranscriptionException.class,
                () -> downloader.download(
                        DOWNLOAD_URL,
                        directory.resolve("input.webm")
                )
        );

        // then
        assertEquals(
                "DOWNLOAD_FAILED",
                exception.getCode()
        );
        assertSame(
                cause,
                exception.getCause()
        );
    }

    @Test
    @DisplayName("S3 리다이렉트 대상에 요청을 보내지 않는다")
    void download_failure_redirect() throws Exception {
        // given
        AtomicBoolean forwarded = new AtomicBoolean();
        HttpServer server = HttpServer.create(
                new InetSocketAddress(
                        "127.0.0.1",
                        0
                ),
                0
        );
        server.createContext(
                "/redirect",
                exchange -> {
                    exchange.getResponseHeaders()
                            .add(
                                    "Location",
                                    "/private"
                            );
                    exchange.sendResponseHeaders(
                            302,
                            -1
                    );
                    exchange.close();
                }
        );
        server.createContext(
                "/private",
                exchange -> {
                    forwarded.set(true);
                    exchange.sendResponseHeaders(
                            200,
                            -1
                    );
                    exchange.close();
                }
        );
        server.start();
        S3AudioDownloader downloader = new S3AudioDownloader(
                TestFixtures.properties(directory),
                uri -> (HttpURLConnection) URI.create(
                        "http://127.0.0.1:" + server.getAddress()
                                .getPort() + "/redirect"
                )
                        .toURL()
                        .openConnection()
        );
        try {
            // when
            TranscriptionException exception = assertThrows(
                    TranscriptionException.class,
                    () -> downloader.download(
                            DOWNLOAD_URL,
                            directory.resolve("input.webm")
                    )
            );

            // then
            assertEquals(
                    "DOWNLOAD_FAILED",
                    exception.getCode()
            );
            assertFalse(forwarded.get());
        } finally {
            server.stop(0);
        }
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"not a uri", "https://169.254.169.254/audio.webm",
            "http://test-bucket.s3.ap-northeast-2.amazonaws.com/audio.webm",
            "https://test-bucket.s3.ap-northeast-2.amazonaws.com:444/audio.webm",
            "https://user@test-bucket.s3.ap-northeast-2.amazonaws.com/audio.webm",
            "https://test-bucket.s3.ap-northeast-2.amazonaws.com/audio.webm#fragment",
            "https://test-bucket.s3.ap-northeast-2.amazonaws.com.attacker.example/audio.webm"})
    @DisplayName("허용된 S3 HTTPS 주소 외에는 거부한다")
    void validate_failure_unsafeUrl(String url) throws Exception {
        // given
        S3AudioDownloader downloader = downloader(new StubConnection());

        // when
        TranscriptionException exception = assertThrows(
                TranscriptionException.class,
                () -> downloader.validate(url)
        );

        // then
        assertEquals(
                "INVALID_DOWNLOAD_URL",
                exception.getCode()
        );
    }

    @Test
    @DisplayName("HTTPS 기본 포트와 서명 쿼리를 그대로 유지한다")
    void validate_success_signedUrl() throws Exception {
        // given
        String url = DOWNLOAD_URL.replace(
                ".com/",
                ".com:443/"
        );
        S3AudioDownloader downloader = downloader(new StubConnection());

        // when
        URI uri = downloader.validate(url);

        // then
        assertEquals(
                url,
                uri.toString()
        );
    }

    @ParameterizedTest
    @ValueSource(longs = {-1, 0})
    @DisplayName("내용 길이가 없거나 비어 있으면 거부한다")
    void download_failure_missingLength(long contentLength) throws Exception {
        // given
        StubConnection connection = new StubConnection();
        connection.contentLength = contentLength;
        S3AudioDownloader downloader = downloader(connection);

        // when
        TranscriptionException exception = assertThrows(
                TranscriptionException.class,
                () -> downloader.download(
                        DOWNLOAD_URL,
                        directory.resolve("input.webm")
                )
        );

        // then
        assertEquals(
                "INVALID_AUDIO_METADATA",
                exception.getCode()
        );
        assertFalse(connection.bodyOpened);
    }

    @Test
    @DisplayName("500MiB를 넘는 응답은 본문을 열기 전에 거부한다")
    void download_failure_oversizedLength() throws Exception {
        // given
        StubConnection connection = new StubConnection();
        connection.contentLength = 524_288_001L;
        S3AudioDownloader downloader = downloader(connection);

        // when
        TranscriptionException exception = assertThrows(
                TranscriptionException.class,
                () -> downloader.download(
                        DOWNLOAD_URL,
                        directory.resolve("input.webm")
                )
        );

        // then
        assertEquals(
                "AUDIO_TOO_LARGE",
                exception.getCode()
        );
        assertFalse(connection.bodyOpened);
    }

    @Test
    @DisplayName("선언한 길이보다 많은 바이트를 받으면 중단한다")
    void download_failure_excessBody() throws Exception {
        // given
        StubConnection connection = new StubConnection();
        connection.contentLength = 2;
        S3AudioDownloader downloader = downloader(connection);

        // when
        TranscriptionException exception = assertThrows(
                TranscriptionException.class,
                () -> downloader.download(
                        DOWNLOAD_URL,
                        directory.resolve("input.webm")
                )
        );

        // then
        assertEquals(
                "AUDIO_TOO_LARGE",
                exception.getCode()
        );
    }

    @Test
    @DisplayName("선언한 길이보다 일찍 끝나면 불완전한 다운로드로 처리한다")
    void download_failure_incompleteBody() throws Exception {
        // given
        StubConnection connection = new StubConnection();
        connection.contentLength = 4;
        S3AudioDownloader downloader = downloader(connection);

        // when
        TranscriptionException exception = assertThrows(
                TranscriptionException.class,
                () -> downloader.download(
                        DOWNLOAD_URL,
                        directory.resolve("input.webm")
                )
        );

        // then
        assertEquals(
                "INCOMPLETE_DOWNLOAD",
                exception.getCode()
        );
    }

    @Test
    @DisplayName("지원하는 오디오 MIME이 아닌 응답은 거부한다")
    void download_failure_wrongContentType() throws Exception {
        // given
        StubConnection connection = new StubConnection();
        connection.contentType = "text/html";
        S3AudioDownloader downloader = downloader(connection);

        // when
        TranscriptionException exception = assertThrows(
                TranscriptionException.class,
                () -> downloader.download(
                        DOWNLOAD_URL,
                        directory.resolve("input.webm")
                )
        );

        // then
        assertEquals(
                "INVALID_AUDIO_METADATA",
                exception.getCode()
        );
        assertFalse(connection.bodyOpened);
    }

    @Test
    @DisplayName("응답이 멈춰도 전체 제한 시간에 연결을 끊는다")
    void download_failure_deadline() throws Exception {
        // given
        StubConnection connection = new StubConnection();
        connection.stalled = true;
        S3AudioDownloader downloader = downloader(connection);

        // when
        TranscriptionException exception = assertThrows(
                TranscriptionException.class,
                () -> downloader.download(
                        DOWNLOAD_URL,
                        directory.resolve("input.webm")
                )
        );

        // then
        assertEquals(
                "DOWNLOAD_TIMEOUT",
                exception.getCode()
        );
        assertTrue(connection.disconnected.getCount() == 0);
    }

    @Test
    @DisplayName("인터럽트된 작업은 다운로드를 시작하지 않는다")
    void download_failure_interrupted() throws Exception {
        // given
        StubConnection connection = new StubConnection();
        S3AudioDownloader downloader = downloader(connection);
        Thread.currentThread()
                .interrupt();
        try {
            // when
            TranscriptionException exception = assertThrows(
                    TranscriptionException.class,
                    () -> downloader.download(
                            DOWNLOAD_URL,
                            directory.resolve("input.webm")
                    )
            );

            // then
            assertEquals(
                    "CANCELED",
                    exception.getCode()
            );
            assertFalse(connection.bodyOpened);
        } finally {
            Thread.interrupted();
        }
    }

    private byte[] pcmWav(String layout) {
        byte[] audio = pcmWav();
        if ("canonical".equals(layout)) {
            return audio;
        }
        int chunkPosition = "metadataBeforeData".equals(layout) ? 36 : audio.length;
        ByteBuffer bytes = ByteBuffer.allocate(audio.length + 14)
                .order(ByteOrder.LITTLE_ENDIAN);
        bytes.put(
                audio,
                0,
                chunkPosition
        );
        bytes.put("JUNK".getBytes(StandardCharsets.US_ASCII));
        bytes.putInt(5);
        bytes.put(new byte[6]);
        bytes.put(
                audio,
                chunkPosition,
                audio.length - chunkPosition
        );
        bytes.putInt(
                4,
                bytes.capacity() - 8
        );
        return bytes.array();
    }

    private byte[] pcmWav() {
        ByteBuffer bytes = ByteBuffer.allocate(76)
                .order(ByteOrder.LITTLE_ENDIAN);
        bytes.put("RIFF".getBytes(StandardCharsets.US_ASCII));
        bytes.putInt(68);
        bytes.put("WAVEfmt ".getBytes(StandardCharsets.US_ASCII));
        bytes.putInt(16);
        bytes.putShort((short) 1);
        bytes.putShort((short) 1);
        bytes.putInt(16000);
        bytes.putInt(32000);
        bytes.putShort((short) 2);
        bytes.putShort((short) 16);
        bytes.put("data".getBytes(StandardCharsets.US_ASCII));
        bytes.putInt(32);
        return bytes.array();
    }

    private byte[] invalidWav(String defect) {
        byte[] audio = pcmWav();
        ByteBuffer bytes = ByteBuffer.wrap(audio)
                .order(ByteOrder.LITTLE_ENDIAN);
        switch (defect) {
            case "mimeSpoof" -> audio = new byte[]{0x1a, 0x45, (byte) 0xdf, (byte) 0xa3};
            case "truncated" -> audio = Arrays.copyOf(
                    audio,
                    60
            );
            case "riffLength" -> bytes.putInt(
                    4,
                    67
            );
            case "sampleRate" -> bytes.putInt(
                    24,
                    48000
            );
            case "channels" -> bytes.putShort(
                    22,
                    (short) 2
            );
            case "floatEncoding" -> bytes.putShort(
                    20,
                    (short) 3
            );
            case "sampleWidth" -> bytes.putShort(
                    34,
                    (short) 8
            );
            case "unalignedData" -> bytes.putInt(
                    40,
                    31
            );
            case "missingData" -> bytes.putInt(
                    36,
                    0
            );
            case "trailingBytes" -> {
                audio = Arrays.copyOf(
                        audio,
                        77
                );
                ByteBuffer.wrap(audio)
                        .order(ByteOrder.LITTLE_ENDIAN)
                        .putInt(
                                4,
                                69
                        );
            }
            case "truncatedChunk" -> {
                audio = Arrays.copyOf(
                        audio,
                        84
                );
                ByteBuffer trailer = ByteBuffer.wrap(audio)
                        .order(ByteOrder.LITTLE_ENDIAN);
                trailer.putInt(
                        4,
                        76
                );
                trailer.putInt(
                        76,
                        0x4b4e554a
                );
                trailer.putInt(
                        80,
                        8
                );
            }
            default -> throw new IllegalArgumentException("알 수 없는 손상 유형입니다");
        }
        return audio;
    }

    private S3AudioDownloader downloader(StubConnection connection) {
        return new S3AudioDownloader(
                TestFixtures.properties(directory),
                uri -> connection
        );
    }

    private static class StubConnection extends HttpURLConnection {
        private final CountDownLatch disconnected = new CountDownLatch(1);
        private byte[] body = new byte[]{1, 2, 3};
        private long contentLength = 3;
        private String contentType = "audio/webm";
        private boolean bodyOpened;
        private boolean stalled;

        private StubConnection() throws Exception {
            super(
                    URI.create(DOWNLOAD_URL)
                            .toURL()
            );
        }

        @Override
        public int getResponseCode() throws IOException {
            if (stalled) {
                try {
                    if (!disconnected.await(
                            5,
                            TimeUnit.SECONDS
                    )) {
                        throw new AssertionError("다운로드 제한 시간에 연결을 끊지 않았습니다");
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread()
                            .interrupt();
                }
                throw new IOException("연결이 닫혔습니다");
            }
            return 200;
        }

        @Override
        public String getContentType() {
            return contentType;
        }

        @Override
        public long getContentLengthLong() {
            return contentLength;
        }

        @Override
        public InputStream getInputStream() {
            bodyOpened = true;
            return new ByteArrayInputStream(body);
        }

        @Override
        public void disconnect() {
            disconnected.countDown();
        }

        @Override
        public boolean usingProxy() {
            return false;
        }

        @Override
        public void connect() {}
    }
}
