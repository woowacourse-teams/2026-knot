package com.knot.backend.testsupport;

import com.knot.backend.recording.domain.model.RecordingConnectionPolicy;
import java.time.Duration;

public final class RecordingConnectionPolicyFixture {
    public static final Duration GRACE = Duration.ofMinutes(30);
    public static final Duration DISCONNECTED_AFTER = Duration.ofSeconds(30);
    public static final RecordingConnectionPolicy DEFAULT = new RecordingConnectionPolicy(
            GRACE,
            DISCONNECTED_AFTER
    );

    private RecordingConnectionPolicyFixture() {}
}
