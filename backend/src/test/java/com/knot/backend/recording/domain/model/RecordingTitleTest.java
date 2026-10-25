package com.knot.backend.recording.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.knot.backend.recording.domain.RecordingErrorCode;
import com.knot.backend.recording.domain.RecordingException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

class RecordingTitleTest {

    @Test
    @DisplayName("AI 제목의 앞뒤 공백을 지우고 안쪽 연속 공백을 한 칸으로 줄인다")
    void normalize_success_collapsesWhitespace() {
        // given
        String generated = "  회원 탈퇴\n\t정책   논의 ";

        // when
        String title = RecordingTitle.normalize(generated);

        // then
        assertThat(title).isEqualTo("회원 탈퇴 정책 논의");
    }

    @Test
    @DisplayName("정리한 제목이 100자이면 그대로 받는다")
    void normalize_success_maxLength() {
        // given
        String generated = "가".repeat(RecordingTitle.MAX_LENGTH);

        // when
        String title = RecordingTitle.normalize(generated);

        // then
        assertThat(title).hasSize(100);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "   ", "　\n"})
    @DisplayName("비어 있거나 공백뿐인 제목은 거절한다")
    void normalize_fail_blank(String generated) {
        // when & then
        assertThatThrownBy(() -> RecordingTitle.normalize(generated)).isInstanceOf(RecordingException.class)
                .extracting("errorCode")
                .isEqualTo(RecordingErrorCode.INVALID_RECORDING_TITLE);
    }

    @Test
    @DisplayName("정리한 제목이 100자를 넘으면 자르지 않고 거절한다")
    void normalize_fail_tooLong() {
        // given
        String generated = "가".repeat(RecordingTitle.MAX_LENGTH + 1);

        // when & then
        assertThatThrownBy(() -> RecordingTitle.normalize(generated)).isInstanceOf(RecordingException.class)
                .extracting("errorCode")
                .isEqualTo(RecordingErrorCode.INVALID_RECORDING_TITLE);
    }

    @Test
    @DisplayName("공개할 AI 제목이 있으면 표시 제목은 그 제목이다")
    void display_success_aiTitle() {
        // when
        String title = RecordingTitle.display(
                "회원 탈퇴 정책 논의",
                "루덴스"
        );

        // then
        assertThat(title).isEqualTo("회원 탈퇴 정책 논의");
    }

    @Test
    @DisplayName("공개할 AI 제목이 없으면 시작한 Member 닉네임으로 대체 이름을 만든다")
    void display_success_fallback() {
        // when
        String title = RecordingTitle.display(
                null,
                "루덴스"
        );

        // then
        assertThat(title).isEqualTo("루덴스 님의 녹음");
    }
}
