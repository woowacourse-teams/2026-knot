package com.knot.backend.auth.presentation.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;

import ch.qos.logback.classic.Level;
import com.knot.backend.auth.application.AuthService;
import com.knot.backend.auth.domain.AuthErrorCode;
import com.knot.backend.auth.domain.AuthException;
import com.knot.backend.auth.domain.model.OAuthProvider;
import com.knot.backend.auth.domain.model.OAuthUser;
import com.knot.backend.auth.infrastructure.oauth.OAuthLoginUser;
import com.knot.backend.auth.presentation.AuthCookieManager;
import com.knot.backend.global.config.JwtProperties;
import com.knot.backend.global.config.OAuth2LoginProperties;
import com.knot.backend.testsupport.LogCapture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;

class OAuthLoggingTest {

    @Test
    @DisplayName("OAuth 성공 처리의 내부 원인은 ERROR와 원인 관계를 남기고 쿠키·302를 유지한다")
    void onSuccess_failure_internalCause() throws Exception {
        AuthService service = mock(AuthService.class);
        AuthException wrapped = new AuthException(
                AuthErrorCode.AUTHENTICATION_INTERNAL_ERROR,
                new IllegalStateException("private-refresh-secret")
        );
        wrapped.addSuppressed(new IllegalArgumentException("private-credential"));
        when(service.login(any())).thenThrow(wrapped);
        OAuthLoginUser principal = mock(OAuthLoginUser.class);
        when(principal.getOAuthUser()).thenReturn(
                OAuthUser.of(
                        OAuthProvider.GITHUB,
                        "42",
                        null
                )
        );
        Authentication authentication = mock(Authentication.class);
        when(authentication.getPrincipal()).thenReturn(principal);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.getSession();
        MockHttpServletResponse response = new MockHttpServletResponse();
        try (LogCapture logs = new LogCapture(OAuth2AuthenticationSuccessHandler.class)) {
            new OAuth2AuthenticationSuccessHandler(
                    service,
                    properties(),
                    cookies()
            ).onAuthenticationSuccess(
                    request,
                    response,
                    authentication
            );
            assertFailureResponse(
                    request,
                    response
            );
            assertThat(logs.events()).singleElement()
                    .satisfies(event -> {
                        assertThat(event.getLevel()).isEqualTo(Level.ERROR);
                        assertThat(event.getFormattedMessage()).contains(
                                "cause=",
                                "suppressed=",
                                "IllegalStateException"
                        )
                                .doesNotContain(
                                        "private-refresh-secret",
                                        "private-credential"
                                );
                        assertThat(event.getThrowableProxy()).isNull();
                    });
        }
    }

    @Test
    @DisplayName("OAuth 승인 취소·인증 거절·내부 장애를 DEBUG·WARN·ERROR로 구분한다")
    void onFailure_failure_classifiesLevel() throws Exception {
        OAuth2AuthenticationFailureHandler handler = new OAuth2AuthenticationFailureHandler(
                properties(),
                cookies()
        );
        try (LogCapture logs = new LogCapture(OAuth2AuthenticationFailureHandler.class)) {
            handler.onAuthenticationFailure(
                    new MockHttpServletRequest(),
                    new MockHttpServletResponse(),
                    new OAuth2AuthenticationException(
                            new OAuth2Error(
                                    "access_denied",
                                    "private-description",
                                    "private-uri"
                            )
                    )
            );
            handler.onAuthenticationFailure(
                    new MockHttpServletRequest(),
                    new MockHttpServletResponse(),
                    new OAuth2AuthenticationException(new OAuth2Error("invalid_state_parameter"))
            );
            MockHttpServletRequest request = new MockHttpServletRequest();
            request.getSession();
            MockHttpServletResponse response = new MockHttpServletResponse();
            handler.onAuthenticationFailure(
                    request,
                    response,
                    new OAuth2AuthenticationException(
                            new OAuth2Error("oauth_error"),
                            "private-description",
                            new IllegalStateException("private-sql")
                    )
            );
            assertFailureResponse(
                    request,
                    response
            );
            assertThat(logs.events()).extracting(event -> event.getLevel())
                    .containsExactly(
                            Level.DEBUG,
                            Level.WARN,
                            Level.ERROR
                    );
            assertThat(logs.events()).allSatisfy(event -> {
                assertThat(event.getFormattedMessage()).doesNotContain(
                        "private-description",
                        "private-uri",
                        "private-sql"
                );
                assertThat(event.getThrowableProxy()).isNull();
            });
        }
    }

    private OAuth2LoginProperties properties() {
        OAuth2LoginProperties properties = new OAuth2LoginProperties();
        properties.setFailureRedirectUri("/login?error=oauth2");
        return properties;
    }

    private AuthCookieManager cookies() {
        return new AuthCookieManager(new JwtProperties());
    }

    private void assertFailureResponse(
            MockHttpServletRequest request,
            MockHttpServletResponse response
    ) {
        assertThat(response.getStatus()).isEqualTo(302);
        assertThat(response.getRedirectedUrl()).isEqualTo("/login?error=oauth2");
        assertThat(request.getSession(false)).isNull();
        assertThat(response.getCookies()).isNotEmpty()
                .allSatisfy(cookie -> assertThat(cookie.getMaxAge()).isZero());
    }
}
