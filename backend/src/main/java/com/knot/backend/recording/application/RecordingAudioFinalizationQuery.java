package com.knot.backend.recording.application;

import com.knot.backend.recording.application.dto.result.AudioFrameRange;
import com.knot.backend.recording.application.dto.result.RecordingAudioSaveResult;
import com.knot.backend.recording.domain.model.AudioManifestPart;
import com.knot.backend.recording.domain.model.RecordingAudioFinalization;
import com.knot.backend.recording.domain.repository.RecordingAudioFinalizationRepository;
import com.knot.backend.recording.domain.repository.RecordingSessionRepository;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Component
@RequiredArgsConstructor
public class RecordingAudioFinalizationQuery {
    private final RecordingAudioFinalizationRepository jobs;
    private final RecordingSessionRepository sessions;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final com.knot.backend.recording.domain.repository.RecordingAudioStreamRepository streams;

    @Transactional(readOnly = true)
    public RecordingAudioSaveResult enrich(
            long recordingId,
            RecordingAudioSaveResult state
    ) {
        return jobs.findByRecordingId(recordingId)
                .map(
                        job -> enrich(
                                recordingId,
                                state,
                                job
                        )
                )
                .orElse(state);
    }

    private RecordingAudioSaveResult enrich(
            long recordingId,
            RecordingAudioSaveResult state,
            RecordingAudioFinalization job
    ) {
        String status = job.audioSaveStatus();
        boolean expired = sessions.findById(recordingId)
                .map(
                        session -> session.getEndedAt() != null && !clock.instant()
                                .isBefore(
                                        session.getEndedAt()
                                                .plus(Duration.ofDays(30))
                                )
                )
                .orElse(true);
        if ("DISCARDED".equals(state.audioSaveStatus())) {
            status = "DISCARDED";
        }
        List<AudioFrameRange> missing = state.missingRanges();
        if (job.getManifestJson() != null) {
            missing = missingRanges(
                    mapper.readValue(
                            job.getManifestJson(),
                            AudioManifestPart[].class
                    )
            );
        }
        return new RecordingAudioSaveResult(
                status,
                state.verifiedThroughSequence(),
                state.verifiedThroughFrame(),
                state.verifiedAudioMillis(),
                state.verifiedRanges(),
                missing,
                state.tailUnknown(),
                state.expectedLastSequence(),
                state.acceptUntil(),
                job.getFailureCode(),
                !expired && !"DISCARDED".equals(status) && job.canRetry(),
                job.getId(),
                job.getQuality()
        );
    }

    @Transactional(readOnly = true)
    public com.knot.backend.recording.application.dto.result.RecordingSourceAudioQuality sourceQuality(
            long recordingId
    ) {
        return jobs.findByRecordingId(recordingId)
                .filter(job -> job.getReadyAt() != null && job.getManifestJson() != null && job.getQuality() != null)
                .map(
                        job -> new com.knot.backend.recording.application.dto.result.RecordingSourceAudioQuality(
                                job.getQuality(),
                                missingRanges(
                                        mapper.readValue(
                                                job.getManifestJson(),
                                                AudioManifestPart[].class
                                        )
                                ),
                                streamsTailUnknown(recordingId)
                        )
                )
                .orElse(null);
    }

    private boolean streamsTailUnknown(long recordingId) {
        return streams.findByRecordingId(recordingId)
                .map(stream -> stream.isTailUnknown())
                .orElse(false);
    }

    private List<AudioFrameRange> missingRanges(AudioManifestPart[] manifest) {
        List<AudioFrameRange> ranges = new ArrayList<>();
        for (AudioManifestPart part : manifest) {
            if (!part.isMissing()) {
                continue;
            }
            addMissingRange(
                    ranges,
                    part
            );
        }
        return ranges;
    }
    private void addMissingRange(
            List<AudioFrameRange> ranges,
            AudioManifestPart part
    ) {
        long end = part.sampleOffset() + part.frameCount();
        if (!ranges.isEmpty() && ranges.getLast()
                .endExclusive() == part.sampleOffset()) {
            AudioFrameRange previous = ranges.removeLast();
            ranges.add(
                    new AudioFrameRange(
                            previous.start(),
                            end
                    )
            );
            return;
        }
        ranges.add(
                new AudioFrameRange(
                        part.sampleOffset(),
                        end
                )
        );
    }

}
