package com.knot.backend.recording.application;

import com.knot.backend.global.config.TranscriptionProperties;
import com.knot.backend.recording.domain.model.RecordingAudioUpload;
import com.knot.backend.recording.domain.model.RecordingTranscription;
import com.knot.backend.recording.domain.model.TranscriptionStatus;
import com.knot.backend.recording.domain.repository.RecordingAudioUploadRepository;
import com.knot.backend.recording.domain.repository.RecordingTranscriptionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

// 홈 진행 카드와 녹음 문서가 같은 기준으로 전사 실패와 재시도 가능 여부를 판정한다.
@Component
@RequiredArgsConstructor
public class RecordingTranscriptionFailure {
    private final RecordingTranscriptionRepository transcriptions;
    private final RecordingAudioUploadRepository uploads;
    private final TranscriptionProperties transcriptionProperties;

    public boolean isFailed(long recordingId) {
        return transcriptions.findByRecordingSessionId(recordingId)
                .map(RecordingTranscription::getStatus)
                .filter(TranscriptionStatus.FAILED::equals)
                .isPresent();
    }

    // 전사 재시도 접수 조건: 전사 기능이 켜져 있고 보관 오디오를 쓸 수 있다. 재시도 횟수·경과 기간은 조건이 아니다.
    public boolean isRetryable(long recordingId) {
        return transcriptionProperties.enabled() && uploads.findByRecordingId(recordingId)
                .filter(RecordingAudioUpload::isAudioAvailable)
                .isPresent();
    }
}
