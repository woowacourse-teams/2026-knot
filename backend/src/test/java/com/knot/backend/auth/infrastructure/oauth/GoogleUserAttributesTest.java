package com.knot.backend.auth.infrastructure.oauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.knot.backend.auth.domain.AuthErrorCode;
import com.knot.backend.auth.domain.AuthException;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class GoogleUserAttributesTest {

    @Test
    @DisplayName("Google 응답에서 회원 번호와 사진 주소만 꺼낸다")
    void convert_success() {
        // given
        Map<String, Object> attributes = Map.of(
                "sub",
                "google-42",
                "name",
                "홍길동",
                "picture",
                "https://example.com/google.png"
        );

        // when
        GoogleUserAttributes result = GoogleUserAttributes.from(attributes);

        // then
        assertThat(result).isEqualTo(
                new GoogleUserAttributes(
                        "google-42",
                        "https://example.com/google.png"
                )
        );
    }

    @Test
    @DisplayName("Google 응답에 사진이 없으면 사진 없이 변환한다")
    void convert_success_withoutPicture() {
        // given
        Map<String, Object> attributes = Map.of(
                "sub",
                "google-42"
        );

        // when
        GoogleUserAttributes result = GoogleUserAttributes.from(attributes);

        // then
        assertThat(result.picture()).isNull();
    }

    @Test
    @DisplayName("Google 응답에 회원 번호가 없으면 커스텀 인증 예외를 발생시킨다")
    void convert_failure_missingSub() {
        // given
        Map<String, Object> attributes = Map.of(
                "picture",
                "https://example.com/google.png"
        );

        // when
        Throwable thrown = catchThrowable(() -> GoogleUserAttributes.from(attributes));

        // then
        assertThat(thrown).isInstanceOfSatisfying(
                AuthException.class,
                exception -> assertThat(exception.getErrorCode()).isEqualTo(AuthErrorCode.INVALID_OAUTH_USER)
        );
    }
}
