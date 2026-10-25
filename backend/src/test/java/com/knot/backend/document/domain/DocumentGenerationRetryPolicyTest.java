package com.knot.backend.document.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.knot.backend.document.domain.model.DocumentGenerationFailureCause;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class DocumentGenerationRetryPolicyTest {

    private final DocumentGenerationRetryPolicy policy = new DocumentGenerationRetryPolicy();

    @ParameterizedTest
    @EnumSource(value = DocumentGenerationFailureCause.class, names = {"TIMEOUT", "RATE_LIMITED", "UNAVAILABLE",
            "INVALID_RESPONSE", "EXECUTION_EXPIRED", "STORAGE"})
    @DisplayName("일시적 장애와 예외적인 응답은 기존 자동 재시도 정책을 적용한다")
    void isAutomaticallyRetryable_success_transientFailure(DocumentGenerationFailureCause cause) {
        assertThat(policy.isAutomaticallyRetryable(cause)).isTrue();
    }

    @ParameterizedTest
    @EnumSource(value = DocumentGenerationFailureCause.class, names = {"AUTHENTICATION", "INVALID_INPUT", "INPUT_LIMIT",
            "OUTPUT_LIMIT", "WORKSPACE_DELETED", "INTERNAL", "LEGACY_FAILURE"})
    @DisplayName("인증·입력·토큰 한도·원인 불명 실패에는 자동 재시도를 추가하지 않는다")
    void isAutomaticallyRetryable_failure_permanentOrUnknownFailure(DocumentGenerationFailureCause cause) {
        assertThat(policy.isAutomaticallyRetryable(cause)).isFalse();
    }
}
