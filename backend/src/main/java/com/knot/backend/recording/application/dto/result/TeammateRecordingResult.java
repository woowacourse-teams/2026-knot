package com.knot.backend.recording.application.dto.result;

import com.knot.backend.recording.domain.model.RecordingSession;
import com.knot.backend.recording.domain.model.RecordingTitle;
import java.time.Instant;

public record TeammateRecordingResult(
        long recordingId,
        CurrentRecordingStatus status,
        String title,
        Instant startedAt,
        long elapsedMillis
) {

    // 진행 중 녹음에는 공개한 AI 제목이 없어 언제나 '닉네임 님의 녹음'이다.
    public static TeammateRecordingResult of(
            TeammateRecordingCandidate candidate,
            Instant now
    ) {
        RecordingSession session = candidate.session();
        return new TeammateRecordingResult(
                session.getId(),
                CurrentRecordingStatus.valueOf(
                        session.getStatus()
                                .name()
                ),
                RecordingTitle.display(
                        null,
                        candidate.starterNickname()
                ),
                session.getStartedAt(),
                session.getRecordingDurationMillis(now)
        );
    }
}
