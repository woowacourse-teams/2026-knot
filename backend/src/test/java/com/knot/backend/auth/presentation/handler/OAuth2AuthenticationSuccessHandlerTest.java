package com.knot.backend.auth.presentation.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.knot.backend.auth.application.AuthService;
import com.knot.backend.auth.application.dto.result.AuthLoginResult;
import com.knot.backend.auth.domain.AuthErrorCode;
import com.knot.backend.auth.domain.AuthException;
import com.knot.backend.auth.domain.model.OAuthProvider;
import com.knot.backend.auth.domain.model.OAuthUser;
import com.knot.backend.auth.infrastructure.oauth.OAuthLoginUser;
import com.knot.backend.auth.presentation.AuthCookieManager;
import com.knot.backend.global.config.JwtProperties;
import com.knot.backend.global.config.OAuth2LoginProperties;
import jakarta.servlet.http.Cookie;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Arrays;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.user.OAuth2User;

class OAuth2AuthenticationSuccessHandlerTest {

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("OAuth 인증 성공 시 JWT 쿠키를 발급하고 설정된 URI로 redirect한다")
    void onAuthenticationSuccess_success_issuesCookieAndRedirects() throws Exception {
        // given
        AuthService authService = mock(AuthService.class);
        JwtProperties jwtProperties = jwtProperties();
        assertThat(jwtProperties.getRefreshCookieName()).isEqualTo("__Host-KNOT_REFRESH_TOKEN");
        assertThat(jwtProperties.isSecure()).isFalse();
        OAuth2LoginProperties loginProperties = new OAuth2LoginProperties();
        loginProperties.setSuccessRedirectUri("/api/v1/auth/me");
        OAuth2AuthenticationSuccessHandler handler = new OAuth2AuthenticationSuccessHandler(
                authService,
                loginProperties,
                new AuthCookieManager(jwtProperties)
        );
        OAuthUser oauthUser = OAuthUser.of(
                OAuthProvider.GITHUB,
                "42",
                null
        );
        OAuth2User delegate = mock(OAuth2User.class);
        doReturn(List.of(new SimpleGrantedAuthority("ROLE_USER"))).when(delegate)
                .getAuthorities();
        when(delegate.getAttributes()).thenReturn(
                Map.of(
                        "id",
                        42L
                )
        );
        OAuthLoginUser loginUser = OAuthLoginUser.of(
                oauthUser,
                delegate
        );
        Authentication authentication = mock(Authentication.class);
        when(authentication.getPrincipal()).thenReturn(loginUser);
        when(authService.login(oauthUser)).thenReturn(
                AuthLoginResult.authenticated(
                        "jwt-token",
                        "refresh-token",
                        Duration.ofDays(7)
                )
        );
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.getSession();
        SecurityContextHolder.getContext()
                .setAuthentication(authentication);
        MockHttpServletResponse response = new MockHttpServletResponse();

        // when
        handler.onAuthenticationSuccess(
                request,
                response,
                authentication
        );

        // then
        assertThat(response.getRedirectedUrl()).isEqualTo("/api/v1/auth/me");
        Cookie cookie = lastCookie(
                response,
                "KNOT_ACCESS_TOKEN"
        );
        assertThat(cookie).isNotNull();
        assertThat(cookie.getValue()).isEqualTo("jwt-token");
        assertThat(cookie.isHttpOnly()).isTrue();
        assertThat(cookie.getSecure()).isFalse();
        assertThat(cookie.getPath()).isEqualTo("/");
        assertThat(cookie.getMaxAge()).isEqualTo(3600);
        assertThat(response.getHeader("Set-Cookie")).contains("SameSite=Lax");
        Cookie refreshCookie = lastCookie(
                response,
                "KNOT_REFRESH_TOKEN"
        );
        assertThat(refreshCookie).isNotNull();
        assertThat(refreshCookie.getValue()).isEqualTo("refresh-token");
        assertThat(refreshCookie.getMaxAge()).isEqualTo(7 * 86400);
        assertThat(
                lastCookie(
                        response,
                        "KNOT_NICKNAME_TOKEN"
                ).getMaxAge()
        ).isZero();
        assertThat(request.getSession(false)).isNull();
        assertThat(
                SecurityContextHolder.getContext()
                        .getAuthentication()
        ).isNull();
    }

    @Test
    @DisplayName("처음 OAuth 인증한 사용자는 닉네임 쿠키를 받고 설정 화면으로 이동한다")
    void onAuthenticationSuccess_success_nicknameSetupRequired() throws Exception {
        // given
        AuthService authService = mock(AuthService.class);
        JwtProperties jwtProperties = jwtProperties();
        OAuth2LoginProperties loginProperties = new OAuth2LoginProperties();
        loginProperties.setNicknameRedirectUri("/nickname");
        OAuth2AuthenticationSuccessHandler handler = new OAuth2AuthenticationSuccessHandler(
                authService,
                loginProperties,
                new AuthCookieManager(jwtProperties)
        );
        OAuthUser oauthUser = OAuthUser.of(
                OAuthProvider.GITHUB,
                "42",
                null
        );
        OAuth2User delegate = mock(OAuth2User.class);
        doReturn(List.of(new SimpleGrantedAuthority("ROLE_USER"))).when(delegate)
                .getAuthorities();
        when(delegate.getAttributes()).thenReturn(
                Map.of(
                        "id",
                        42L
                )
        );
        OAuthLoginUser loginUser = OAuthLoginUser.of(
                oauthUser,
                delegate
        );
        Authentication authentication = mock(Authentication.class);
        when(authentication.getPrincipal()).thenReturn(loginUser);
        when(authService.login(oauthUser)).thenReturn(AuthLoginResult.nicknameSetupRequired("nickname-token"));
        MockHttpServletResponse response = new MockHttpServletResponse();

        // when
        handler.onAuthenticationSuccess(
                new MockHttpServletRequest(),
                response,
                authentication
        );

        // then
        assertThat(response.getRedirectedUrl()).isEqualTo("/nickname");
        Cookie cookie = lastCookie(
                response,
                "KNOT_NICKNAME_TOKEN"
        );
        assertThat(cookie).isNotNull();
        assertThat(cookie.getValue()).isEqualTo("nickname-token");
        assertThat(
                lastCookie(
                        response,
                        "KNOT_ACCESS_TOKEN"
                ).getMaxAge()
        ).isZero();
        assertThat(
                lastCookie(
                        response,
                        "KNOT_REFRESH_TOKEN"
                ).getMaxAge()
        ).isZero();
    }

    @Test
    @DisplayName("OAuth 인증 주체가 로그인 사용자가 아니면 커스텀 인증 예외를 발생시킨다")
    void onAuthenticationSuccess_failure_invalidPrincipal() throws Exception {
        // given
        AuthService authService = mock(AuthService.class);
        JwtProperties jwtProperties = jwtProperties();
        OAuth2LoginProperties loginProperties = new OAuth2LoginProperties();
        OAuth2AuthenticationSuccessHandler handler = new OAuth2AuthenticationSuccessHandler(
                authService,
                loginProperties,
                new AuthCookieManager(jwtProperties)
        );
        Authentication authentication = mock(Authentication.class);
        when(authentication.getPrincipal()).thenReturn("invalid-principal");
        MockHttpServletResponse response = new MockHttpServletResponse();

        // when
        handler.onAuthenticationSuccess(
                new MockHttpServletRequest(),
                response,
                authentication
        );

        // then
        assertThat(response.getRedirectedUrl()).isEqualTo("/login?error=oauth2");
        assertThat(response.getCookies()).isNotEmpty()
                .allSatisfy(cookie -> assertThat(cookie.getMaxAge()).isZero());
        verify(
                authService,
                never()
        ).login(any());
    }

    @Test
    @DisplayName("로그인 애플리케이션 서비스 실패를 안전한 오류 redirect로 변환한다")
    void onAuthenticationSuccess_failure_applicationError() throws Exception {
        // given
        AuthService authService = mock(AuthService.class);
        JwtProperties jwtProperties = jwtProperties();
        OAuth2LoginProperties loginProperties = new OAuth2LoginProperties();
        OAuth2AuthenticationSuccessHandler handler = new OAuth2AuthenticationSuccessHandler(
                authService,
                loginProperties,
                new AuthCookieManager(jwtProperties)
        );
        OAuthUser oauthUser = OAuthUser.of(
                OAuthProvider.GITHUB,
                "42",
                null
        );
        OAuth2User delegate = mock(OAuth2User.class);
        doReturn(List.of(new SimpleGrantedAuthority("ROLE_USER"))).when(delegate)
                .getAuthorities();
        when(delegate.getAttributes()).thenReturn(
                Map.of(
                        "id",
                        42L
                )
        );
        OAuthLoginUser loginUser = OAuthLoginUser.of(
                oauthUser,
                delegate
        );
        Authentication authentication = mock(Authentication.class);
        when(authentication.getPrincipal()).thenReturn(loginUser);
        when(authService.login(oauthUser)).thenThrow(new AuthException(AuthErrorCode.OAUTH_AUTHENTICATION_FAILED));
        MockHttpServletResponse response = new MockHttpServletResponse();

        // when
        handler.onAuthenticationSuccess(
                new MockHttpServletRequest(),
                response,
                authentication
        );

        // then
        assertThat(response.getRedirectedUrl()).isEqualTo("/login?error=oauth2");
    }

    @Test
    @DisplayName("OAuth 성공 redirect 설정이 비어 있으면 설정 예외를 발생시킨다")
    void create_failure_blankSuccessRedirectUri() {
        // given
        AuthService authService = mock(AuthService.class);
        JwtProperties jwtProperties = jwtProperties();
        OAuth2LoginProperties loginProperties = new OAuth2LoginProperties();
        loginProperties.setSuccessRedirectUri(" ");

        // when
        Throwable thrown = catchThrowable(
                () -> new OAuth2AuthenticationSuccessHandler(
                        authService,
                        loginProperties,
                        new AuthCookieManager(jwtProperties)
                )
        );

        // then
        assertThat(thrown).isInstanceOfSatisfying(
                AuthException.class,
                exception -> assertThat(exception.getErrorCode()).isEqualTo(AuthErrorCode.OAUTH_CONFIGURATION_INVALID)
        );
    }

    @Test
    @DisplayName("OAuth 실패 redirect 설정이 비어 있으면 설정 예외를 발생시킨다")
    void create_failure_blankFailureRedirectUri() {
        // given
        AuthService authService = mock(AuthService.class);
        JwtProperties jwtProperties = jwtProperties();
        OAuth2LoginProperties loginProperties = new OAuth2LoginProperties();
        loginProperties.setFailureRedirectUri(" ");

        // when
        Throwable thrown = catchThrowable(
                () -> new OAuth2AuthenticationSuccessHandler(
                        authService,
                        loginProperties,
                        new AuthCookieManager(jwtProperties)
                )
        );

        // then
        assertThat(thrown).isInstanceOfSatisfying(
                AuthException.class,
                exception -> assertThat(exception.getErrorCode()).isEqualTo(AuthErrorCode.OAUTH_CONFIGURATION_INVALID)
        );
    }

    private JwtProperties jwtProperties() {
        JwtProperties properties = new JwtProperties();
        properties.setExpiration(Duration.ofHours(1));
        properties.setSecure(false);
        return properties;
    }

    private Cookie lastCookie(
            MockHttpServletResponse response,
            String name
    ) {
        return Arrays.stream(response.getCookies())
                .filter(cookie -> name.equals(cookie.getName()))
                .reduce(
                        (
                                previous,
                                current
                        ) -> current
                )
                .orElseThrow();
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    @DisplayName("예상하지 못한 저장 오류도 credential 없이 로그를 남기고 실패 화면으로 이동한다")
    void onAuthenticationSuccess_failure_redactsInternalError(CapturedOutput output) throws Exception {
        // given
        AuthService authService = mock(AuthService.class);
        OAuthUser user = OAuthUser.of(
                OAuthProvider.GITHUB,
                "42",
                null
        );
        OAuth2User delegate = mock(OAuth2User.class);
        OAuthLoginUser loginUser = OAuthLoginUser.of(
                user,
                delegate
        );
        Authentication authentication = mock(Authentication.class);
        when(authentication.getPrincipal()).thenReturn(loginUser);
        when(authService.login(user)).thenThrow(new RuntimeException("private-refresh-secret"));
        OAuth2AuthenticationSuccessHandler handler = new OAuth2AuthenticationSuccessHandler(
                authService,
                new OAuth2LoginProperties(),
                new AuthCookieManager(jwtProperties())
        );
        MockHttpServletResponse response = new MockHttpServletResponse();

        // when
        handler.onAuthenticationSuccess(
                new MockHttpServletRequest(),
                response,
                authentication
        );

        // then
        assertThat(response.getRedirectedUrl()).isEqualTo("/login?error=oauth2");
        assertThat(response.getCookies()).allSatisfy(cookie -> assertThat(cookie.getValue()).isEmpty());
        assertThat(response.getContentAsString()).isEmpty();
        assertThat(output.getAll()).doesNotContain("private-refresh-secret");
    }
}
