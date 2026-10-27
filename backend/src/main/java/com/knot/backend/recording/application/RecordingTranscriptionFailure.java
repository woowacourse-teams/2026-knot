package com.knot.backend.recording.application;

import com.knot.backend.global.config.TranscriptionProperties;
import com.knot.backend.recording.domain.model.RecordingAudioUpload;
import com.knot.backend.recording.domain.model.RecordingTranscription;
import com.knot.backend.recording.domain.model.TranscriptionStatus;
import com.knot.backend.recording.domain.repository.RecordingAudioUploadRepository;
import com.knot.backend.recording.domain.repository.RecordingTranscriptionRepository;
import java.util.Collection;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

// 홈 진행 카드·녹음 문서·녹음 기록 목록이 같은 기준으로 전사 실패와 재시도 가능 여부를 판정한다.
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

    // 녹음 묶음에서 현재 전사 시도가 실패한 녹음 ID다. 녹음 수와 관계없이 한 번 읽는다.
    public Set<Long> findFailed(Collection<Long> recordingIds) {
        if (recordingIds.isEmpty()) {
            return Set.of();
        }
        return transcriptions.findAllByRecordingSessionIdIn(recordingIds)
                .stream()
                .filter(transcription -> transcription.getStatus() == TranscriptionStatus.FAILED)
                .map(RecordingTranscription::getRecordingSessionId)
                .collect(Collectors.toSet());
    }

    // 녹음 묶음에서 전사 재시도 접수 조건을 채운 녹음 ID다. 단건 isRetryable과 같은 조건이다.
    public Set<Long> findRetryable(Collection<Long> recordingIds) {
        if (!transcriptionProperties.enabled() || recordingIds.isEmpty()) {
            return Set.of();
        }
        return uploads.findAllByRecordingIdIn(recordingIds)
                .stream()
                .filter(RecordingAudioUpload::isAudioAvailable)
                .map(RecordingAudioUpload::getRecordingId)
                .collect(Collectors.toSet());
    }
}
