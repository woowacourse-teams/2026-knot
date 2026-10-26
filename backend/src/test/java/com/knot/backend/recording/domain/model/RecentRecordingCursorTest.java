package com.knot.backend.recording.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.knot.backend.recording.domain.RecordingErrorCode;
import com.knot.backend.recording.domain.RecordingException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class RecentRecordingCursorTest {
    private static final Instant STARTED_AT = Instant.parse("2026-10-22T01:30:00.123456Z");

    @Test
    @DisplayName("커서는 Workspace와 마이크로초 시작 시각·녹음 ID를 그대로 되살린다")
    void parse_success_roundTrip() {
        // given
        String encoded = RecentRecordingCursor.of(
                1,
                STARTED_AT,
                512
        )
                .encode();

        // when
        RecentRecordingCursor cursor = RecentRecordingCursor.parse(
                encoded,
                1
        );

        // then
        assertThat(cursor.getStartedAt()).isEqualTo(STARTED_AT);
        assertThat(cursor.getRecordingId()).isEqualTo(512);
        assertThat(cursor.encode()).isEqualTo(encoded);
    }

    @Test
    @DisplayName("다른 Workspace에서 받은 커서는 거절한다")
    void parse_fail_otherWorkspace() {
        // given
        String encoded = RecentRecordingCursor.of(
                1,
                STARTED_AT,
                512
        )
                .encode();

        // when & then
        assertInvalid(encoded);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "not-a-cursor!"})
    @DisplayName("빈 값이나 인코딩이 깨진 커서는 거절한다")
    void parse_fail_invalidEncoding(String encoded) {
        // when & then
        assertInvalid(encoded);
    }

    @ParameterizedTest
    @ValueSource(strings = {"2|2|2026-10-22T01:30:00Z|512", "1|2|2026-10-22T01:30:00Z", "1|2|yesterday|512",
            "1|2|2026-10-22T01:30:00Z|0", "1|x|2026-10-22T01:30:00Z|512"})
    @DisplayName("버전·필드 수·시각·ID 형식이 맞지 않는 커서는 거절한다")
    void parse_fail_invalidPayload(String payload) {
        // given
        String encoded = Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(payload.getBytes(StandardCharsets.UTF_8));

        // when & then
        assertInvalid(encoded);
    }

    private void assertInvalid(String encoded) {
        assertThatThrownBy(
                () -> RecentRecordingCursor.parse(
                        encoded,
                        2
                )
        ).isInstanceOf(RecordingException.class)
                .extracting("errorCode")
                .isEqualTo(RecordingErrorCode.INVALID_PARAMETER);
    }
}
