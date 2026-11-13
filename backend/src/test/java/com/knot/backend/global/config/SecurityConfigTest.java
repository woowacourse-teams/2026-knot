package com.knot.backend.global.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.knot.backend.auth.infrastructure.oauth.OAuthLoginUserService;
import com.knot.backend.auth.infrastructure.jwt.JwtAuthenticationFilter;
import com.knot.backend.auth.presentation.handler.AuthAccessDeniedHandler;
import com.knot.backend.auth.presentation.handler.AuthAuthenticationEntryPoint;
import com.knot.backend.document.presentation.DocumentTopicAuthenticationEntryPoint;
import com.knot.backend.auth.presentation.handler.AuthLogoutSuccessHandler;
import com.knot.backend.auth.presentation.handler.JwtLogoutHandler;
import com.knot.backend.auth.presentation.handler.OAuth2AuthenticationFailureHandler;
import com.knot.backend.auth.presentation.handler.OAuth2AuthenticationSuccessHandler;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.DefaultCorsProcessor;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

class SecurityConfigTest {

    @Test
    @DisplayName("CORS 설정은 개발 Origin과 마지막으로 본 워크스페이스 갱신에 필요한 PUT을 허용한다")
    void corsConfigurationSource_success_allowsDevelopmentOriginsAndPutMethod() {
        // given
        SecurityConfig securityConfig = securityConfig();

        // when
        UrlBasedCorsConfigurationSource source = securityConfig.corsConfigurationSource();
        MockHttpServletRequest request = new MockHttpServletRequest(
                HttpMethod.PUT.name(),
                "/api/v1/members/me/last-viewed-workspace"
        );
        CorsConfiguration configuration = source.getCorsConfiguration(request);

        // then
        assertThat(configuration).isNotNull();
        assertThat(configuration.getAllowedMethods()).contains(
                HttpMethod.GET.name(),
                HttpMethod.POST.name(),
                HttpMethod.PUT.name(),
                HttpMethod.OPTIONS.name()
        );
        assertThat(configuration.getAllowedOrigins()).containsExactly(
                "https://dev.knoted.kr",
                "http://localhost:3000"
        );
    }

    @Test
    @DisplayName("CORS 설정은 워크스페이스 탈퇴에 필요한 DELETE를 허용하고 기존 Method와 헤더를 유지한다")
    void corsConfigurationSource_success_allowsDeleteMethodForWorkspaceLeave() {
        // given
        SecurityConfig securityConfig = securityConfig();
        UrlBasedCorsConfigurationSource source = securityConfig.corsConfigurationSource();
        MockHttpServletRequest request = new MockHttpServletRequest(
                HttpMethod.DELETE.name(),
                "/api/v1/workspaces/1/members/me"
        );

        // when
        CorsConfiguration configuration = source.getCorsConfiguration(request);

        // then
        assertThat(configuration).isNotNull();
        assertThat(configuration.getAllowedMethods()).containsExactly(
                HttpMethod.GET.name(),
                HttpMethod.POST.name(),
                HttpMethod.PUT.name(),
                HttpMethod.PATCH.name(),
                HttpMethod.DELETE.name(),
                HttpMethod.OPTIONS.name()
        );
        assertThat(configuration.getAllowedHeaders()).containsExactly(
                HttpHeaders.CONTENT_TYPE,
                "X-XSRF-TOKEN"
        );
        assertThat(configuration.getAllowCredentials()).isTrue();
        assertThat(configuration.getExposedHeaders()).containsExactly(
                "Retry-After",
                "X-Request-ID"
        );
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/v1/workspaces/1/recordings/1/speaker-count",
            "/api/v1/workspaces/1/recordings/1/speakers/1"})
    @DisplayName("개발 화면의 화자 설정 PATCH 사전 요청은 인증 쿠키와 CSRF 헤더를 허용한다")
    void corsConfigurationSource_success_allowsSpeakerSettingsPreflight(String path) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(
                HttpMethod.OPTIONS.name(),
                path
        );
        request.addHeader(
                HttpHeaders.ORIGIN,
                "https://dev.knoted.kr"
        );
        request.addHeader(
                HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD,
                HttpMethod.PATCH.name()
        );
        request.addHeader(
                HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS,
                "content-type,x-xsrf-token"
        );
        MockHttpServletResponse response = new MockHttpServletResponse();
        CorsConfiguration configuration = securityConfig().corsConfigurationSource()
                .getCorsConfiguration(request);

        boolean accepted = new DefaultCorsProcessor().processRequest(
                configuration,
                request,
                response
        );

        assertThat(accepted).isTrue();
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN)).isEqualTo("https://dev.knoted.kr");
        assertThat(response.getHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_METHODS)).contains(HttpMethod.PATCH.name());
        assertThat(response.getHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS)).isEqualTo("true");
        assertThat(response.getHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS)).containsIgnoringCase("x-xsrf-token");
    }

    private SecurityConfig securityConfig() {
        return new SecurityConfig(
                mock(OAuthLoginUserService.class),
                mock(JwtAuthenticationFilter.class),
                mock(OAuth2AuthenticationSuccessHandler.class),
                mock(OAuth2AuthenticationFailureHandler.class),
                mock(AuthAuthenticationEntryPoint.class),
                mock(DocumentTopicAuthenticationEntryPoint.class),
                mock(AuthAccessDeniedHandler.class),
                mock(JwtLogoutHandler.class),
                mock(AuthLogoutSuccessHandler.class),
                jwtProperties(),
                corsProperties(),
                new ApiDocumentationProperties()
        );
    }

    private JwtProperties jwtProperties() {
        JwtProperties jwtProperties = new JwtProperties();
        jwtProperties.setSecret("test-secret-test-secret-test-secret-test-secret");
        jwtProperties.setCookieName("__Host-KNOT_ACCESS_TOKEN");
        jwtProperties.setNicknameCookieName("__Host-KNOT_NICKNAME_TOKEN");
        jwtProperties.setExpiration(Duration.ofHours(1));
        jwtProperties.setSecure(false);
        return jwtProperties;
    }

    private CorsProperties corsProperties() {
        CorsProperties corsProperties = new CorsProperties();
        corsProperties.setAllowedOrigins(
                List.of(
                        "https://dev.knoted.kr",
                        "http://localhost:3000"
                )
        );
        return corsProperties;
    }
}
