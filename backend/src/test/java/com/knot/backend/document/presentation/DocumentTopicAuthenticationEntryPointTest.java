package com.knot.backend.document.presentation;

import static org.assertj.core.api.Assertions.assertThat;

import com.knot.backend.global.config.JwtProperties;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import tools.jackson.databind.ObjectMapper;

class DocumentTopicAuthenticationEntryPointTest {
    private static final String COOKIE_NAME = "__Host-KNOT_ACCESS_TOKEN";

    @Test
    @DisplayName("인증 쿠키 없이 다른 쿠키만 있으면 UNAUTHENTICATED다")
    void commence_failure_missingAuthenticationCookie() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(
                new Cookie(
                        "XSRF-TOKEN",
                        "csrf"
                )
        );
        MockHttpServletResponse response = commence(request);
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains("UNAUTHENTICATED");
    }

    @Test
    @DisplayName("설정된 운영 인증 쿠키의 인증 실패만 INVALID_JWT로 구분한다")
    void commence_failure_invalidAuthenticationCookie() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(
                new Cookie(
                        COOKIE_NAME,
                        "private-invalid-token"
                )
        );
        MockHttpServletResponse response = commence(request);
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains("INVALID_JWT")
                .doesNotContain("private-invalid-token");
    }

    private MockHttpServletResponse commence(MockHttpServletRequest request) throws Exception {
        JwtProperties properties = new JwtProperties();
        properties.setCookieName(COOKIE_NAME);
        DocumentTopicAuthenticationEntryPoint entryPoint = new DocumentTopicAuthenticationEntryPoint(
                new ObjectMapper(),
                properties
        );
        MockHttpServletResponse response = new MockHttpServletResponse();
        entryPoint.commence(
                request,
                response,
                new InsufficientAuthenticationException("authentication required")
        );
        return response;
    }
}
