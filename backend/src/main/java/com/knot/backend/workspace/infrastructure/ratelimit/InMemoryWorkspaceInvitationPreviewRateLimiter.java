package com.knot.backend.workspace.infrastructure.ratelimit;

import com.knot.backend.workspace.application.WorkspaceInvitationPreviewRateLimitException;
import com.knot.backend.workspace.application.WorkspaceInvitationPreviewRateLimiter;
import com.knot.backend.workspace.domain.WorkspaceErrorCode;
import com.knot.backend.workspace.domain.WorkspaceException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class InMemoryWorkspaceInvitationPreviewRateLimiter implements WorkspaceInvitationPreviewRateLimiter {
    static final int DEFAULT_MAX_TRACKED_ADDRESSES = 10_000;
    private static final int MAX_REQUESTS_PER_WINDOW = 30;
    private static final Duration WINDOW = Duration.ofMinutes(1);
    private static final long MIN_RETRY_AFTER_SECONDS = 1L;
    private static final String UNKNOWN_ADDRESS_KEY = "<unknown>";
    private static final String OVERFLOW_KEY = "<overflow>";

    private final Map<String, WindowCounter> counters = new HashMap<>();
    private final Clock clock;
    private final int maxTrackedAddresses;
    private Instant nextCleanupAt = Instant.MIN;

    @Autowired
    public InMemoryWorkspaceInvitationPreviewRateLimiter(Clock clock) {
        this(
                clock,
                DEFAULT_MAX_TRACKED_ADDRESSES
        );
    }

    InMemoryWorkspaceInvitationPreviewRateLimiter(
            Clock clock,
            int maxTrackedAddresses
    ) {
        this.clock = Objects.requireNonNull(
                clock,
                "clock"
        );
        if (maxTrackedAddresses < 2) {
            throw new WorkspaceException(WorkspaceErrorCode.WORKSPACE_INVITATION_SECURITY_CONFIGURATION_INVALID);
        }
        this.maxTrackedAddresses = maxTrackedAddresses;
    }

    @Override
    public synchronized void consume(String remoteAddress) {
        Instant now = Instant.now(clock);
        cleanupExpiredCounters(now);
        String key = resolveCounterKey(remoteAddress);
        counters.put(
                key,
                consumeOrStartWindow(
                        counters.get(key),
                        now
                )
        );
    }

    private void cleanupExpiredCounters(Instant now) {
        if (now.isBefore(nextCleanupAt)) {
            return;
        }
        counters.entrySet()
                .removeIf(
                        entry -> entry.getValue()
                                .isExpiredAt(now)
                );
        nextCleanupAt = now.plus(WINDOW);
    }

    private String resolveCounterKey(String remoteAddress) {
        String requestedKey = remoteAddress == null || remoteAddress.isBlank() ? UNKNOWN_ADDRESS_KEY : remoteAddress;
        if (counters.containsKey(requestedKey)) {
            return requestedKey;
        }
        if (counters.size() >= maxTrackedAddresses - 1) {
            return OVERFLOW_KEY;
        }
        return requestedKey;
    }

    private WindowCounter consumeOrStartWindow(
            WindowCounter counter,
            Instant now
    ) {
        if (counter == null || counter.isExpiredAt(now)) {
            return WindowCounter.startedAt(now);
        }
        return counter.consume(now);
    }

    private record WindowCounter(
            Instant windowStartedAt,
            int requestCount
    ) {

        private static WindowCounter startedAt(Instant now) {
            return new WindowCounter(
                    now,
                    1
            );
        }

        private boolean isExpiredAt(Instant now) {
            return !now.isBefore(windowStartedAt.plus(WINDOW));
        }

        private WindowCounter consume(Instant now) {
            if (requestCount >= MAX_REQUESTS_PER_WINDOW) {
                throw new WorkspaceInvitationPreviewRateLimitException(retryAfterSeconds(now));
            }
            return new WindowCounter(
                    windowStartedAt,
                    requestCount + 1
            );
        }

        private long retryAfterSeconds(Instant now) {
            long remainingNanos = Duration.between(
                    now,
                    windowStartedAt.plus(WINDOW)
            )
                    .toNanos();
            long seconds = (remainingNanos + 999_999_999L) / 1_000_000_000L;
            return Math.max(
                    MIN_RETRY_AFTER_SECONDS,
                    seconds
            );
        }
    }
}
