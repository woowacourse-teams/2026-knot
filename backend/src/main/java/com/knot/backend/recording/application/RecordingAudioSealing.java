package com.knot.backend.recording.application;

import com.knot.backend.recording.domain.RecordingErrorCode;
import com.knot.backend.recording.domain.RecordingException;
import com.knot.backend.recording.domain.model.RecordingSession;
import com.knot.backend.recording.domain.model.RecordingStatus;
import com.knot.backend.recording.domain.model.RecordingAudioMode;
import com.knot.backend.recording.domain.model.RecordingAudioStream;
import com.knot.backend.recording.domain.model.RecordingAudioSealKind;
import com.knot.backend.recording.domain.repository.RecordingAudioStreamRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class RecordingAudioSealing {
    private final RecordingAudioStreamRepository streams;

    public void sealEnded(RecordingSession session) {
        if (session.getAudioMode() != RecordingAudioMode.PCM16_CHUNKS_V1
                || session.getStatus() != RecordingStatus.ENDED) {
            return;
        }
        RecordingAudioStream stream = streams.findByRecordingIdForUpdate(session.getId())
                .orElseThrow(() -> new RecordingException(RecordingErrorCode.RECORDING_NOT_FOUND));
        RecordingAudioSealKind kind = switch (session.getEndReason()) {
            case CONNECTION_EXPIRED -> RecordingAudioSealKind.TIMEOUT;
            case MAX_DURATION -> RecordingAudioSealKind.LIMIT;
            case USER_ENDED -> RecordingAudioSealKind.REMOTE;
        };
        stream.seal(
                kind,
                session.getEndedAt()
        );
        streams.save(stream);
    }
}
