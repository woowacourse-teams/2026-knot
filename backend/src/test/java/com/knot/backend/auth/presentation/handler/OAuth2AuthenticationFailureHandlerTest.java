package com.knot.backend.auth.presentation.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.knot.backend.auth.domain.AuthErrorCode;
import com.knot.backend.auth.domain.AuthException;
import com.knot.backend.global.config.OAuth2LoginProperties;
import com.knot.backend.global.config.JwtProperties;
import com.knot.backend.auth.presentation.AuthCookieManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;

class OAuth2AuthenticationFailureHandlerTest {

    @Test
    @DisplayName("OAuth 인증 실패를 로그인 오류 화면으로 redirect한다")
    void onAuthenticationFailure_success_redirectsToLogin() throws Exception {
        // given
        OAuth2LoginProperties loginProperties = new OAuth2LoginProperties();
        loginProperties.setFailureRedirectUri("/login?error=oauth2");
        OAuth2AuthenticationFailureHandler handler = new OAuth2AuthenticationFailureHandler(
                loginProperties,
                new AuthCookieManager(new JwtProperties())
        );
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.getSession();
        MockHttpServletResponse response = new MockHttpServletResponse();
        OAuth2AuthenticationException exception = new OAuth2AuthenticationException(new OAuth2Error("oauth_error"));

        // when
        handler.onAuthenticationFailure(
                request,
                response,
                exception
        );

        // then
        assertThat(response.getRedirectedUrl()).isEqualTo("/login?error=oauth2");
        assertThat(request.getSession(false)).isNull();
        assertThat(response.getStatus()).isEqualTo(302);
        assertThat(response.getContentAsString()).isEmpty();
        assertThat(response.getCookies()).isNotEmpty()
                .allSatisfy(cookie -> {
                    assertThat(cookie.getMaxAge()).isZero();
                    assertThat(cookie.getValue()).isEmpty();
                });
    }

    @Test
    @DisplayName("OAuth 승인을 취소하면 취소 오류 값으로 로그인 화면에 redirect한다")
    void onAuthenticationFailure_success_redirectsCancelledToLogin() throws Exception {
        // given
        OAuth2LoginProperties loginProperties = new OAuth2LoginProperties();
        loginProperties.setFailureRedirectUri("/login?error=oauth2");
        OAuth2AuthenticationFailureHandler handler = new OAuth2AuthenticationFailureHandler(
                loginProperties,
                new AuthCookieManager(new JwtProperties())
        );
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.getSession();
        MockHttpServletResponse response = new MockHttpServletResponse();
        OAuth2AuthenticationException exception = new OAuth2AuthenticationException(new OAuth2Error("access_denied"));

        // when
        handler.onAuthenticationFailure(
                request,
                response,
                exception
        );

        // then
        assertThat(response.getRedirectedUrl()).isEqualTo("/login?error=oauth2_cancelled");
        assertThat(request.getSession(false)).isNull();
        assertThat(response.getCookies()).isNotEmpty()
                .allSatisfy(cookie -> assertThat(cookie.getMaxAge()).isZero());
    }

    @Test
    @DisplayName("절대 주소 실패 redirect 설정에서도 취소는 error 값만 바꿔 redirect한다")
    void onAuthenticationFailure_success_redirectsCancelledToAbsoluteLogin() throws Exception {
        // given
        OAuth2LoginProperties loginProperties = new OAuth2LoginProperties();
        loginProperties.setFailureRedirectUri("https://knoted.kr/login?error=oauth2");
        OAuth2AuthenticationFailureHandler handler = new OAuth2AuthenticationFailureHandler(
                loginProperties,
                new AuthCookieManager(new JwtProperties())
        );
        MockHttpServletResponse response = new MockHttpServletResponse();
        OAuth2AuthenticationException exception = new OAuth2AuthenticationException(new OAuth2Error("access_denied"));

        // when
        handler.onAuthenticationFailure(
                new MockHttpServletRequest(),
                response,
                exception
        );

        // then
        assertThat(response.getRedirectedUrl()).isEqualTo("https://knoted.kr/login?error=oauth2_cancelled");
    }

    @Test
    @DisplayName("OAuth 실패 redirect 설정이 비어 있으면 설정 예외를 발생시킨다")
    void create_failure_blankRedirectUri() {
        // given
        OAuth2LoginProperties loginProperties = new OAuth2LoginProperties();
        loginProperties.setFailureRedirectUri(" ");

        // when
        Throwable thrown = catchThrowable(
                () -> new OAuth2AuthenticationFailureHandler(
                        loginProperties,
                        new AuthCookieManager(new JwtProperties())
                )
        );

        // then
        assertThat(thrown).isInstanceOfSatisfying(
                AuthException.class,
                exception -> assertThat(exception.getErrorCode()).isEqualTo(AuthErrorCode.OAUTH_CONFIGURATION_INVALID)
        );
    }
}
