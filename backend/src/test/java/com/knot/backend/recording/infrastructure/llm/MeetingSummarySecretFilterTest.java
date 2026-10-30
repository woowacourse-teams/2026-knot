package com.knot.backend.recording.infrastructure.llm;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class MeetingSummarySecretFilterTest {
    @Test
    void masksAuthenticationCodeEvenWhenRepeatedWithoutItsLabel() {
        String original = "인증코드가 483921 이라고 말했다. 사용자 1000명이 목표다.";
        String result = "인증 코드 483921를 입력했다. 번호 483921을 확인했다. 사용자 1000명이 목표다.";
        assertThat(
                MeetingSummarySecretFilter.filter(
                        original,
                        result
                )
        ).doesNotContain("483921")
                .contains("1000");
    }

    @Test
    void masksCodeIntroducedInOutputAndKeepsUnrelatedNumbers() {
        String result = "인증 번호(758346)를 확인한다. 목표는 1000명이다.";
        assertThat(
                MeetingSummarySecretFilter.filter(
                        "회의 내용",
                        result
                )
        ).doesNotContain("758346")
                .contains("1000");
    }

    @Test
    void doesNotTreatPartOfLongerNumberAsAuthenticationCode() {
        String text = "인증번호와 관련된 접수 번호 123456789를 확인한다.";

        assertThat(
                MeetingSummarySecretFilter.filter(
                        text,
                        text
                )
        ).isEqualTo(text);
    }
}
