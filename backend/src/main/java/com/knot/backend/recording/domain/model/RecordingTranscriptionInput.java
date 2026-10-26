package com.knot.backend.recording.domain.model;

import com.knot.backend.recording.domain.RecordingErrorCode;
import com.knot.backend.recording.domain.RecordingException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.Getter;

@Getter
@Entity
@Table(name = "recording_transcription_inputs")
public class RecordingTranscriptionInput {
    public static final String PIPELINE = TranscriptionWindowShape.PREPARED_TWO_MINUTES.pipelineVersion();

    @Id
    private Long recordingId;

    @Column(nullable = false, updatable = false)
    private String sourceStorageKey;

    @Column(nullable = false, updatable = false)
    private String sourceVersionId;

    @Column(nullable = false, updatable = false)
    private String sourceContentType;

    @Column(nullable = false, updatable = false)
    private long sourceContentLength;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private InputPreparationStatus status;

    @Column(nullable = false)
    private UUID attemptId;

    private UUID ownerToken;

    @Column(nullable = false)
    private long claimEpoch;

    private Instant leaseUntil;

    private Instant executionDeadline;

    @Column(nullable = false)
    private Instant nextRunAt;

    private String failureCode;

    private String storageKey;

    private String storageVersionId;

    private String checksumSha256;

    private long byteLength;

    private long totalFrames;

    protected RecordingTranscriptionInput() {}

    public static RecordingTranscriptionInput queued(
            long recordingId,
            String key,
            String version,
            String contentType,
            long length,
            Instant now
    ) {
        if (recordingId <= 0 || key == null || key.isBlank() || !validVersion(version) || contentType == null
                || contentType.isBlank() || length <= 0 || now == null) {
            throw invalid();
        }
        RecordingTranscriptionInput input = new RecordingTranscriptionInput();
        input.recordingId = recordingId;
        input.sourceStorageKey = key;
        input.sourceVersionId = version;
        input.sourceContentType = contentType;
        input.sourceContentLength = length;
        input.attemptId = UUID.randomUUID();
        input.status = InputPreparationStatus.QUEUED;
        input.nextRunAt = now;
        return input;
    }

    public String getPipelineVersion() {
        return PIPELINE;
    }

    /** 배포 전에 30초 구간으로 계획된 준비 입력도 같은 버전으로 끝까지 처리한다. */
    public static List<String> windowPipelineVersions() {
        return List.of(
                TranscriptionWindowShape.PREPARED_30_SECONDS.pipelineVersion(),
                PIPELINE
        );
    }

    public boolean claim(
            UUID owner,
            Instant now,
            Duration lease
    ) {
        if (owner == null || now == null || lease == null || lease.isZero() || lease.isNegative()) {
            throw invalid();
        }
        boolean eligible = status == InputPreparationStatus.QUEUED && !now.isBefore(nextRunAt)
                || status == InputPreparationStatus.PROCESSING && !now.isBefore(leaseUntil);
        if (!eligible || executionDeadline != null && !now.isBefore(executionDeadline)) {
            return false;
        }
        if (executionDeadline == null) {
            executionDeadline = now.plus(Duration.ofMinutes(15));
        }
        ownerToken = owner;
        claimEpoch++;
        leaseUntil = now.plus(lease)
                .isBefore(executionDeadline) ? now.plus(lease) : executionDeadline;
        status = InputPreparationStatus.PROCESSING;
        return true;
    }

    public boolean owned(
            UUID attempt,
            UUID owner,
            long epoch,
            Instant now
    ) {
        return status == InputPreparationStatus.PROCESSING && attemptId.equals(attempt) && ownerToken.equals(owner)
                && claimEpoch == epoch && now.isBefore(leaseUntil) && now.isBefore(executionDeadline);
    }

    public boolean renew(
            UUID attempt,
            UUID owner,
            long epoch,
            Instant now,
            Duration lease
    ) {
        if (!owned(
                attempt,
                owner,
                epoch,
                now
        )) {
            return false;
        }
        if (lease == null || lease.isZero() || lease.isNegative()) {
            throw invalid();
        }
        leaseUntil = now.plus(lease)
                .isBefore(executionDeadline) ? now.plus(lease) : executionDeadline;
        return true;
    }

    public void ready(
            String key,
            StoredAudioChunk stored,
            long frames
    ) {
        if (status != InputPreparationStatus.PROCESSING || frames <= 0 || frames > 115_200_000 || stored == null
                || !stored.exists() || stored.byteLength() != 44 + frames * 2
                || !"audio/wav".equals(stored.contentType()) || !validVersion(stored.versionId())
                || stored.checksumSha256() == null || !stored.checksumSha256()
                        .matches("[A-Za-z0-9+/]{43}=")
                || key == null
                || !key.matches("recordings/" + recordingId + "/prepared/" + attemptId + "/[a-f0-9]{64}\\.wav")) {
            throw invalid();
        }
        String expectedKey = "recordings/" + recordingId + "/prepared/" + attemptId + "/" + java.util.HexFormat.of()
                .formatHex(
                        java.util.Base64.getDecoder()
                                .decode(stored.checksumSha256())
                ) + ".wav";
        if (!expectedKey.equals(key)) {
            throw invalid();
        }
        storageKey = key;
        storageVersionId = stored.versionId();
        checksumSha256 = stored.checksumSha256();
        byteLength = stored.byteLength();
        totalFrames = frames;
        status = InputPreparationStatus.READY;
        ownerToken = null;
        leaseUntil = null;
    }

    public void fail(String code) {
        if (status != InputPreparationStatus.PROCESSING && status != InputPreparationStatus.QUEUED) {
            return;
        }
        if (code == null || !code.matches("[A-Z0-9_]{1,100}")) {
            throw invalid();
        }
        status = InputPreparationStatus.FAILED;
        failureCode = code;
        ownerToken = null;
        leaseUntil = null;
    }

    public boolean expire(Instant now) {
        if ((status == InputPreparationStatus.PROCESSING || status == InputPreparationStatus.QUEUED)
                && executionDeadline != null && !now.isBefore(executionDeadline)) {
            fail("INPUT_PREPARATION_TIMEOUT");
            return true;
        }
        return false;
    }

    public void retry(Instant now) {
        if (status != InputPreparationStatus.FAILED) {
            return;
        }
        attemptId = UUID.randomUUID();
        status = InputPreparationStatus.QUEUED;
        executionDeadline = null;
        nextRunAt = now;
        failureCode = null;
    }

    public void cancel() {
        status = InputPreparationStatus.CANCELLED;
        failureCode = null;
        ownerToken = null;
        leaseUntil = null;
        storageKey = null;
        storageVersionId = null;
        checksumSha256 = null;
        byteLength = 0;
        totalFrames = 0;
    }

    private static boolean validVersion(String value) {
        return value != null && !value.isBlank() && !"null".equals(value);
    }

    private static RecordingException invalid() {
        return new RecordingException(RecordingErrorCode.INVALID_RECORDING_DATA);
    }
}
