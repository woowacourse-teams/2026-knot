package com.knot.ai.transcription.presentation;

import com.knot.ai.transcription.application.TranscriptionJobService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class TranscriptionMetricsController {
    private final TranscriptionJobService service;

    public TranscriptionMetricsController(TranscriptionJobService service) {
        this.service = service;
    }

    @GetMapping(value = "/internal/metrics", produces = "text/plain;version=0.0.4;charset=utf-8")
    public String getMetrics() {
        return service.getMetrics();
    }
}
