package com.knot.backend.recording.presentation.dto.response;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.knot.backend.recording.application.dto.result.RecordingAudioPlaybackResult;
import com.knot.backend.recording.application.dto.result.RecordingAudioPlaybackStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

public record RecordingAudioPlaybackResponse(
        @Schema(description = "녹음 세션 ID", example = "512", requiredMode = REQUIRED) long recordingId,
        @Schema(description = "음성 상태. AVAILABLE(들을 수 있음), PREPARING(구간 업로드 녹음의 음성 최종화 대기·진행), "
                + "NO_AUDIO(최종 음성 파일이 정리됐거나 저장소 버전 기록이 없음). 녹음 문서의 processingStatus와 따로 계산한다", example = "AVAILABLE", requiredMode = REQUIRED) RecordingAudioPlaybackStatus audioStatus,
        @Schema(description = "최종 음성 파일의 버전 고정 서명 다운로드 URL. <audio src>에 crossorigin 없이 넣는다. AVAILABLE일 때만 값이 있다", nullable = true, requiredMode = REQUIRED) String playbackUrl,
        @Schema(description = "playbackUrl 만료 시각. 지나면 같은 요청으로 다시 받는다. AVAILABLE일 때만 값이 있다", example = "2026-10-26T10:00:00Z", nullable = true, requiredMode = REQUIRED) Instant expiresAt,
        @Schema(description = "저장된 원본 형식 audio/webm 또는 audio/wav. AVAILABLE일 때만 값이 있다", example = "audio/webm", nullable = true, requiredMode = REQUIRED) String contentType,
        @Schema(description = "일시정지를 뺀 서버 누적 녹음 길이(ms). 재생 위치 0은 전사 구간 startMillis의 0과 같다", example = "1980450", requiredMode = REQUIRED) long recordingDurationMillis
) {

    public static RecordingAudioPlaybackResponse from(RecordingAudioPlaybackResult result) {
        return new RecordingAudioPlaybackResponse(
                result.recordingId(),
                result.audioStatus(),
                result.playbackUrl(),
                result.expiresAt(),
                result.contentType(),
                result.recordingDurationMillis()
        );
    }
}
