package com.knot.backend.recording.application.dto.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.knot.backend.recording.domain.RecordingErrorCode;
import com.knot.backend.recording.domain.RecordingException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class RecentRecordingListParametersTest {

    @Test
    @DisplayName("size를 생략하면 20개씩 조회한다")
    void of_success_defaultSize() {
        // when
        RecentRecordingListParameters parameters = RecentRecordingListParameters.of(
                null,
                null
        );

        // then
        assertThat(parameters.size()).isEqualTo(20);
        assertThat(parameters.cursor()).isNull();
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 100})
    @DisplayName("size는 1 이상 100 이하를 받는다")
    void of_success_sizeBoundary(int size) {
        // when
        RecentRecordingListParameters parameters = RecentRecordingListParameters.of(
                null,
                size
        );

        // then
        assertThat(parameters.size()).isEqualTo(size);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 101, -1})
    @DisplayName("범위 밖 size는 잘못된 파라미터로 거절한다")
    void of_fail_sizeOutOfRange(int size) {
        // when & then
        assertThatThrownBy(
                () -> RecentRecordingListParameters.of(
                        null,
                        size
                )
        ).isInstanceOf(RecordingException.class)
                .extracting("errorCode")
                .isEqualTo(RecordingErrorCode.INVALID_PARAMETER);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " "})
    @DisplayName("빈 cursor는 첫 페이지로 보지 않고 거절한다")
    void of_fail_blankCursor(String cursor) {
        // when & then
        assertThatThrownBy(
                () -> RecentRecordingListParameters.of(
                        cursor,
                        null
                )
        ).isInstanceOf(RecordingException.class)
                .extracting("errorCode")
                .isEqualTo(RecordingErrorCode.INVALID_PARAMETER);
    }
}
