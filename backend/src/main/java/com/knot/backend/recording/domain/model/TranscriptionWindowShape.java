package com.knot.backend.recording.domain.model;

import com.knot.backend.recording.domain.RecordingErrorCode;
import com.knot.backend.recording.domain.RecordingException;
import java.util.Arrays;

/**
 * 구간 전사 pipelineVersion별 구간 길이와 이음매 처리. 녹음은 시작할 때 고정한 버전으로 끝까지 처리하므로 이전 버전도 남겨
 * 둔다. 2분 구간은 미래 음성 없이 직전 10초만 문맥으로 쓰고, 인접 구간의 겹친 단어를 정렬해 이어 붙인다.
 */
public enum TranscriptionWindowShape {
    LIVE_30_SECONDS(
            "pcm-window-v1-30-5",
            30,
            5,
            5,
            false
    ),
    LIVE_TWO_MINUTES(
            "pcm-window-v2-120-10",
            120,
            10,
            0,
            true
    ),
    PREPARED_30_SECONDS(
            "prepared-window-v1-30-5",
            30,
            5,
            5,
            false
    ),
    PREPARED_TWO_MINUTES(
            "prepared-window-v2-120-10",
            120,
            10,
            0,
            true
    );

    /** AI 서버 구간 입력 상한. 새 음성 2분과 직전 10초 */
    public static final long MAX_INPUT_FRAMES = 130 * 16_000L;

    private static final long FRAMES_PER_SECOND = 16_000;

    private final String pipelineVersion;
    private final long coreFrames;
    private final long beforeFrames;
    private final long afterFrames;
    private final boolean seamAligned;

    TranscriptionWindowShape(
            String pipelineVersion,
            long coreSeconds,
            long beforeSeconds,
            long afterSeconds,
            boolean seamAligned
    ) {
        this.pipelineVersion = pipelineVersion;
        this.coreFrames = coreSeconds * FRAMES_PER_SECOND;
        this.beforeFrames = beforeSeconds * FRAMES_PER_SECOND;
        this.afterFrames = afterSeconds * FRAMES_PER_SECOND;
        this.seamAligned = seamAligned;
    }

    public static TranscriptionWindowShape of(String pipelineVersion) {
        return Arrays.stream(values())
                .filter(shape -> shape.pipelineVersion.equals(pipelineVersion))
                .findFirst()
                .orElseThrow(() -> new RecordingException(RecordingErrorCode.INVALID_RECORDING_DATA));
    }

    /**
     * 2분 구간에서만 정책을 정해 AI에 보낸다. 30초 구간은 null이라 요청 모양이 이전과 같다.
     */
    public TranscriptionDecodePolicy decodePolicy(boolean lastWindow) {
        if (!seamAligned) {
            return null;
        }
        if (lastWindow) {
            return TranscriptionDecodePolicy.DEFAULT_ONLY;
        }
        return TranscriptionDecodePolicy.WITH_VOCABULARY;
    }

    public String pipelineVersion() {
        return pipelineVersion;
    }

    public long coreFrames() {
        return coreFrames;
    }

    public long beforeFrames() {
        return beforeFrames;
    }

    public long afterFrames() {
        return afterFrames;
    }

    public boolean seamAligned() {
        return seamAligned;
    }
}
