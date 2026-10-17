package com.knot.backend.recording.infrastructure.scheduling;

import com.knot.backend.recording.application.RecordingTranscriptionService;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class RecordingTranscriptionScheduler {
    private final RecordingTranscriptionService service;

    @Scheduled(fixedDelayString = "${knot.transcription.dispatch-interval:PT1S}")
    public void dispatch() {
        service.dispatch();
    }
}
