package com.knot.backend.auth.infrastructure.oauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.knot.backend.auth.domain.AuthErrorCode;
import com.knot.backend.auth.domain.AuthException;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class GithubUserAttributesTest {

    @Test
    @DisplayName("GitHub 응답을 타입이 정해진 사용자 정보로 변환한다")
    void convert_success() {
        // given
        Map<String, Object> attributes = Map.of(
                "id",
                42L,
                "avatar_url",
                "https://example.com/avatar"
        );

        // when
        GithubUserAttributes result = GithubUserAttributes.from(attributes);

        // then
        assertThat(result.id()).isEqualTo(42L);
        assertThat(result.avatarUrl()).isEqualTo("https://example.com/avatar");
    }

    @Test
    @DisplayName("GitHub 응답의 필수 타입이 다르면 커스텀 인증 예외를 발생시킨다")
    void convert_failure_invalidType() {
        // given
        Map<String, Object> attributes = Map.of(
                "id",
                "42"
        );

        // when
        Throwable thrown = catchThrowable(() -> GithubUserAttributes.from(attributes));

        // then
        assertThat(thrown).isInstanceOfSatisfying(
                AuthException.class,
                exception -> assertThat(exception.getErrorCode()).isEqualTo(AuthErrorCode.INVALID_OAUTH_USER)
        );
    }
}
