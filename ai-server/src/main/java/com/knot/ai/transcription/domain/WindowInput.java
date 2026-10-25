package com.knot.ai.transcription.domain;

public record WindowInput(
        String pipelineVersion,
        String inputManifestSha256,
        long inputStartFrame,
        long inputEndFrame,
        DecodePolicy decodePolicy
) {
    /** 새 음성 2분과 직전 10초 */
    public static final int MAXIMUM_SECONDS = 130;
    private static final long MAXIMUM_FRAMES = MAXIMUM_SECONDS * 16_000L;

    public WindowInput {
        if (pipelineVersion == null || pipelineVersion.isBlank() || pipelineVersion.length() > 64
                || inputManifestSha256 == null || !inputManifestSha256.matches("[0-9a-f]{64}") || inputStartFrame < 0
                || inputEndFrame <= inputStartFrame || inputEndFrame > 115200000
                || inputEndFrame - inputStartFrame > MAXIMUM_FRAMES) {
            throw new TranscriptionException(TranscriptionErrorCode.INVALID_WINDOW);
        }
        if (decodePolicy == null) {
            decodePolicy = DecodePolicy.DEFAULT_ONLY;
        }
    }

    public WindowInput(
            String pipelineVersion,
            String inputManifestSha256,
            long inputStartFrame,
            long inputEndFrame
    ) {
        this(
                pipelineVersion,
                inputManifestSha256,
                inputStartFrame,
                inputEndFrame,
                null
        );
    }
}
