package com.knot.backend.global.config;

import com.knot.backend.auth.domain.AuthErrorCode;
import com.knot.backend.document.presentation.DocumentTopicAuthenticationEntryPoint;
import com.knot.backend.global.logging.RequestIdFilter;
import com.knot.backend.auth.domain.AuthException;
import com.knot.backend.auth.infrastructure.oauth.OAuthLoginUserService;
import com.knot.backend.auth.infrastructure.jwt.JwtAuthenticationFilter;
import com.knot.backend.auth.presentation.handler.AuthAccessDeniedHandler;
import com.knot.backend.auth.presentation.handler.AuthAuthenticationEntryPoint;
import com.knot.backend.auth.presentation.handler.AuthLogoutSuccessHandler;
import com.knot.backend.auth.presentation.handler.JwtLogoutHandler;
import com.knot.backend.auth.presentation.handler.OAuth2AuthenticationFailureHandler;
import com.knot.backend.auth.presentation.handler.OAuth2AuthenticationSuccessHandler;
import lombok.RequiredArgsConstructor;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.authentication.DelegatingAuthenticationEntryPoint;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

@Configuration
@EnableWebSecurity
@EnableConfigurationProperties({JwtProperties.class, OAuth2LoginProperties.class, CorsProperties.class,
        ApiDocumentationProperties.class})
@RequiredArgsConstructor
public class SecurityConfig {
    private final OAuthLoginUserService oauthLoginUserService;
    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final OAuth2AuthenticationSuccessHandler successHandler;
    private final OAuth2AuthenticationFailureHandler failureHandler;
    private final AuthAuthenticationEntryPoint authenticationEntryPoint;
    private final DocumentTopicAuthenticationEntryPoint topicAuthenticationEntryPoint;
    private final AuthAccessDeniedHandler accessDeniedHandler;
    private final JwtLogoutHandler jwtLogoutHandler;
    private final AuthLogoutSuccessHandler logoutSuccessHandler;
    private final JwtProperties jwtProperties;
    private final CorsProperties corsProperties;
    private final ApiDocumentationProperties apiDocumentationProperties;

    @Bean
    public UrlBasedCorsConfigurationSource corsConfigurationSource() {
        List<String> allowedOrigins = corsProperties.getAllowedOrigins();
        if (allowedOrigins == null || allowedOrigins.isEmpty() || allowedOrigins.stream()
                .anyMatch(this::isInvalidCorsOrigin)) {
            throw new AuthException(AuthErrorCode.OAUTH_CONFIGURATION_INVALID);
        }

        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(allowedOrigins);
        configuration.setAllowedMethods(
                List.of(
                        HttpMethod.GET.name(),
                        HttpMethod.POST.name(),
                        HttpMethod.PUT.name(),
                        HttpMethod.DELETE.name(),
                        HttpMethod.OPTIONS.name()
                )
        );
        configuration.setAllowedHeaders(
                List.of(
                        HttpHeaders.CONTENT_TYPE,
                        "X-XSRF-TOKEN"
                )
        );
        configuration.setExposedHeaders(
                List.of(
                        HttpHeaders.RETRY_AFTER,
                        RequestIdFilter.HEADER
                )
        );
        configuration.setAllowCredentials(true);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration(
                "/**",
                configuration
        );
        return source;
    }

    private AuthenticationEntryPoint requestAuthenticationEntryPoint() {
        RequestMatcher topics = PathPatternRequestMatcher.withDefaults()
                .matcher("/api/v1/workspaces/{workspaceId}/document-topics/**");
        return DelegatingAuthenticationEntryPoint.builder()
                .defaultEntryPoint(authenticationEntryPoint)
                .addEntryPointFor(
                        topicAuthenticationEntryPoint,
                        topics
                )
                .build();
    }

    private boolean isInvalidCorsOrigin(String origin) {
        return origin == null || origin.isBlank() || "*".equals(origin);
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        CookieCsrfTokenRepository csrfTokenRepository = CookieCsrfTokenRepository.withHttpOnlyFalse();
        csrfTokenRepository.setCookieCustomizer(
                cookie -> cookie.httpOnly(false)
                        .secure(jwtProperties.isSecure())
                        .sameSite("Lax")
                        .path("/")
        );

        http.cors(Customizer.withDefaults())
                .authorizeHttpRequests(auth -> {
                    auth.requestMatchers(
                            HttpMethod.GET,
                            "/actuator/prometheus"
                    )
                            .access(
                                    (
                                            authentication,
                                            context
                                    ) -> new AuthorizationDecision(
                                            List.of(
                                                    "127.0.0.1",
                                                    "::1",
                                                    "0:0:0:0:0:0:0:1"
                                            )
                                                    .contains(
                                                            context.getRequest()
                                                                    .getRemoteAddr()
                                                    )
                                    )
                            )
                            .requestMatchers(
                                    "/actuator/prometheus",
                                    "/actuator/prometheus/**"
                            )
                            .denyAll();
                    if (apiDocumentationProperties.isEnabled()) {
                        auth.requestMatchers(
                                "/swagger-ui.html",
                                "/swagger-ui/**",
                                "/v3/api-docs",
                                "/v3/api-docs/**",
                                "/v3/api-docs.yaml",
                                "/webjars/**"
                        )
                                .permitAll();
                    }
                    auth.requestMatchers(
                            "/oauth2/**",
                            "/login/**",
                            "/api/v1/auth/nickname",
                            "/api/v1/auth/refresh",
                            "/api/v1/auth/csrf",
                            "/actuator/health",
                            "/error"
                    )
                            .permitAll()
                            .requestMatchers(
                                    HttpMethod.GET,
                                    "/api/v1/invitations/*"
                            )
                            .permitAll()
                            .anyRequest()
                            .authenticated();
                })
                .exceptionHandling(
                        exception -> exception.authenticationEntryPoint(requestAuthenticationEntryPoint())
                                .accessDeniedHandler(accessDeniedHandler)
                )
                .csrf(
                        csrf -> csrf.csrfTokenRepository(csrfTokenRepository)
                                .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler())
                )
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
                .oauth2Login(
                        oauth2 -> oauth2.userInfoEndpoint(userInfo -> userInfo.userService(oauthLoginUserService))
                                .successHandler(successHandler)
                                .failureHandler(failureHandler)
                )
                .logout(
                        logout -> logout.logoutUrl("/api/v1/auth/logout")
                                .addLogoutHandler(jwtLogoutHandler)
                                .logoutSuccessHandler(logoutSuccessHandler)
                )
                .addFilterBefore(
                        jwtAuthenticationFilter,
                        UsernamePasswordAuthenticationFilter.class
                );

        return http.build();
    }
}
