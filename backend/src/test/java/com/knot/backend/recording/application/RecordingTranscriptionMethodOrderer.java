package com.knot.backend.recording.application;

import java.util.Collections;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.MethodOrdererContext;

public class RecordingTranscriptionMethodOrderer implements MethodOrderer {
    private static final String ORDER_ENVIRONMENT_VARIABLE = "KNOT_TRANSCRIPTION_TEST_ORDER";
    private static final String REVERSE_ORDER = "reverse";

    @Override
    public void orderMethods(MethodOrdererContext context) {
        if (REVERSE_ORDER.equals(System.getenv(ORDER_ENVIRONMENT_VARIABLE))) {
            Collections.reverse(context.getMethodDescriptors());
        }
    }
}
