package com.knot.backend.recording.application;

import com.knot.backend.recording.application.dto.result.RecordingAudioPlaybackResult;
import com.knot.backend.recording.domain.RecordingErrorCode;
import com.knot.backend.recording.domain.RecordingException;
import com.knot.backend.recording.domain.model.RecordingAudioUpload;
import com.knot.backend.recording.domain.repository.RecordingAudioUploadRepository;
import com.knot.backend.recording.domain.repository.RecordingSessionRepository;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class RecordingAudioPlaybackService {
    private final RecordingAccess recordingAccess;
    private final RecentRecordingQuery recentRecordingQuery;
    private final RecordingSessionRepository recordingSessionRepository;
    private final RecordingAudioUploadRepository recordingAudioUploadRepository;
    private final RecordingAudioStorage recordingAudioStorage;

    // 저장소 확인을 기다리는 동안 DB 연결을 잡지 않도록 트랜잭션 없이 읽는다.
    // 조회는 녹음·전사·처리 상태와 문서 확인 기록을 바꾸지 않고 재생 기록을 남기지 않는다.
    public RecordingAudioPlaybackResult find(
            long workspaceId,
            long memberId,
            long recordingId
    ) {
        recordingAccess.validateMember(
                workspaceId,
                memberId
        );
        validateOpenable(
                workspaceId,
                recordingId
        );
        long recordingDurationMillis = recordingSessionRepository.findById(recordingId)
                .orElseThrow(() -> new RecordingException(RecordingErrorCode.RECORDING_NOT_FOUND))
                .getAccumulatedRecordingMillis();
        // 열 수 있는 녹음에 완료 업로드가 없으면 구간 업로드 녹음의 음성 최종화가 대기·진행 중이다.
        Optional<RecordingAudioUpload> completedUpload = recordingAudioUploadRepository.findByRecordingId(recordingId)
                .filter(RecordingAudioUpload::isCompleted);
        if (completedUpload.isEmpty()) {
            return RecordingAudioPlaybackResult.preparing(
                    recordingId,
                    recordingDurationMillis
            );
        }
        RecordingAudioUpload upload = completedUpload.get();
        if (!upload.isPlayable()) {
            return RecordingAudioPlaybackResult.noAudio(
                    recordingId,
                    recordingDurationMillis
            );
        }
        return RecordingAudioPlaybackResult.available(
                recordingId,
                recordingAudioStorage.presignPlayback(
                        upload.getStorageKey(),
                        upload.getStorageVersionId(),
                        upload.getContentType(),
                        upload.getContentLength()
                ),
                upload.getContentType(),
                recordingDurationMillis
        );
    }

    private void validateOpenable(
            long workspaceId,
            long recordingId
    ) {
        if (recordingId <= 0) {
            throw new RecordingException(RecordingErrorCode.INVALID_RECORDING_DATA);
        }
        if (!recentRecordingQuery.contains(
                workspaceId,
                recordingId
        )) {
            throw new RecordingException(RecordingErrorCode.RECORDING_NOT_FOUND);
        }
    }
}
