package com.knot.backend.auth.infrastructure.oauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.knot.backend.auth.domain.AuthErrorCode;
import com.knot.backend.auth.domain.AuthException;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class KakaoUserAttributesTest {

    @Test
    @DisplayName("카카오 응답에서 회원 번호와 프로필 사진 주소를 꺼낸다")
    void convert_success() {
        // given
        Map<String, Object> attributes = kakaoAttributes(
                Map.of(
                        "profile_image_url",
                        "https://example.com/kakao.png",
                        "is_default_image",
                        false
                )
        );

        // when
        KakaoUserAttributes result = KakaoUserAttributes.from(attributes);

        // then
        assertThat(result).isEqualTo(
                new KakaoUserAttributes(
                        4242L,
                        "https://example.com/kakao.png"
                )
        );
    }

    @Test
    @DisplayName("카카오 기본 프로필 이미지는 사진 없음으로 변환한다")
    void convert_success_defaultImage() {
        // given
        Map<String, Object> attributes = kakaoAttributes(
                Map.of(
                        "profile_image_url",
                        "https://example.com/default.png",
                        "is_default_image",
                        true
                )
        );

        // when
        KakaoUserAttributes result = KakaoUserAttributes.from(attributes);

        // then
        assertThat(result.profileImageUrl()).isNull();
    }

    @Test
    @DisplayName("프로필 사진 제공에 동의하지 않은 카카오 응답은 사진 없이 변환한다")
    void convert_success_withoutAccount() {
        // given
        Map<String, Object> attributes = Map.of(
                "id",
                4242L
        );

        // when
        KakaoUserAttributes result = KakaoUserAttributes.from(attributes);

        // then
        assertThat(result).isEqualTo(
                new KakaoUserAttributes(
                        4242L,
                        null
                )
        );
    }

    @Test
    @DisplayName("카카오 응답의 회원 번호 타입이 다르면 커스텀 인증 예외를 발생시킨다")
    void convert_failure_invalidIdType() {
        // given
        Map<String, Object> attributes = Map.of(
                "id",
                "4242"
        );

        // when
        Throwable thrown = catchThrowable(() -> KakaoUserAttributes.from(attributes));

        // then
        assertThat(thrown).isInstanceOfSatisfying(
                AuthException.class,
                exception -> assertThat(exception.getErrorCode()).isEqualTo(AuthErrorCode.INVALID_OAUTH_USER)
        );
    }

    private Map<String, Object> kakaoAttributes(Map<String, Object> profile) {
        return Map.of(
                "id",
                4242L,
                "kakao_account",
                Map.of(
                        "profile",
                        profile
                )
        );
    }
}
