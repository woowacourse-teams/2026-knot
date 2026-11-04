package com.knot.backend.auth.domain.model;

import com.knot.backend.auth.domain.AuthErrorCode;
import com.knot.backend.auth.domain.AuthException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class OAuthUserTest {

    @Test
    @DisplayName("유효한 OAuth 사용자 정보를 생성한다")
    void create_success() {
        // given

        // when
        OAuthUser user = OAuthUser.of(
                OAuthProvider.GITHUB,
                "42",
                "https://example.com/avatar"
        );

        // then
        assertThat(user.getProvider()).isEqualTo(OAuthProvider.GITHUB);
        assertThat(user.getExternalId()).isEqualTo("42");
        assertThat(user.getProfileImageUrl()).isEqualTo("https://example.com/avatar");
    }

    @Test
    @DisplayName("OAuth provider가 없으면 커스텀 예외를 발생시킨다")
    void create_failure_nullProvider() {
        // given

        // when
        Throwable thrown = catchThrowable(
                () -> OAuthUser.of(
                        null,
                        "42",
                        null
                )
        );

        // then
        assertThat(thrown).isInstanceOf(AuthException.class);
    }

    @Test
    @DisplayName("OAuth 외부 ID가 비어 있으면 커스텀 예외를 발생시킨다")
    void create_failure_blankExternalId() {
        // given

        // when
        Throwable thrown = catchThrowable(
                () -> OAuthUser.of(
                        OAuthProvider.GITHUB,
                        " ",
                        null
                )
        );

        // then
        assertThat(thrown).isInstanceOfSatisfying(
                AuthException.class,
                exception -> assertThat(exception.getErrorCode()).isEqualTo(AuthErrorCode.INVALID_OAUTH_USER)
        );
    }

    @Test
    @DisplayName("OAuth 외부 ID가 null이면 커스텀 예외를 발생시킨다")
    void create_failure_nullExternalId() {
        // given

        // when
        Throwable thrown = catchThrowable(
                () -> OAuthUser.of(
                        OAuthProvider.GITHUB,
                        null,
                        null
                )
        );

        // then
        assertThat(thrown).isInstanceOf(AuthException.class);
    }

    @Test
    @DisplayName("OAuth 프로필 이미지 URL이 비어 있으면 사진 없이 만든다")
    void create_success_blankProfileImageUrl() {
        // given
        String blankProfileImageUrl = " ";

        // when
        OAuthUser result = OAuthUser.of(
                OAuthProvider.GITHUB,
                "42",
                blankProfileImageUrl
        );

        // then
        assertThat(result.getProfileImageUrl()).isNull();
    }

    @Test
    @DisplayName("OAuth 프로필 이미지 URL이 길이 제한을 초과하면 사진 없이 만든다")
    void create_success_overlongProfileImageUrl() {
        // given
        String overlongProfileImageUrl = "a".repeat(501);

        // when
        OAuthUser result = OAuthUser.of(
                OAuthProvider.KAKAO,
                "42",
                overlongProfileImageUrl
        );

        // then
        assertThat(result.getProfileImageUrl()).isNull();
    }

    @Test
    @DisplayName("OAuth 프로필 이미지 URL이 길이 제한 이내면 그대로 보관한다")
    void create_success_maxLengthProfileImageUrl() {
        // given
        String maxLengthProfileImageUrl = "a".repeat(500);

        // when
        OAuthUser result = OAuthUser.of(
                OAuthProvider.GOOGLE,
                "42",
                maxLengthProfileImageUrl
        );

        // then
        assertThat(result.getProfileImageUrl()).isEqualTo(maxLengthProfileImageUrl);
    }
}
