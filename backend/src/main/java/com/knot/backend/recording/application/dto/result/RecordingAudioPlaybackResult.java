package com.knot.backend.recording.application.dto.result;

import java.time.Instant;

public record RecordingAudioPlaybackResult(
        long recordingId,
        RecordingAudioPlaybackStatus audioStatus,
        String playbackUrl,
        Instant expiresAt,
        String contentType,
        long recordingDurationMillis
) {

    public static RecordingAudioPlaybackResult available(
            long recordingId,
            PresignedAudioPlayback playback,
            String contentType,
            long recordingDurationMillis
    ) {
        return new RecordingAudioPlaybackResult(
                recordingId,
                RecordingAudioPlaybackStatus.AVAILABLE,
                playback.playbackUrl(),
                playback.expiresAt(),
                contentType,
                recordingDurationMillis
        );
    }

    public static RecordingAudioPlaybackResult preparing(
            long recordingId,
            long recordingDurationMillis
    ) {
        return withoutPlayback(
                recordingId,
                RecordingAudioPlaybackStatus.PREPARING,
                recordingDurationMillis
        );
    }

    public static RecordingAudioPlaybackResult noAudio(
            long recordingId,
            long recordingDurationMillis
    ) {
        return withoutPlayback(
                recordingId,
                RecordingAudioPlaybackStatus.NO_AUDIO,
                recordingDurationMillis
        );
    }

    private static RecordingAudioPlaybackResult withoutPlayback(
            long recordingId,
            RecordingAudioPlaybackStatus audioStatus,
            long recordingDurationMillis
    ) {
        return new RecordingAudioPlaybackResult(
                recordingId,
                audioStatus,
                null,
                null,
                null,
                recordingDurationMillis
        );
    }
}
