package com.knot.backend.document.presentation;

import com.knot.backend.auth.domain.AuthErrorCode;
import com.knot.backend.global.config.JwtProperties;
import com.knot.backend.global.response.ErrorResponse;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
@RequiredArgsConstructor
public class DocumentTopicAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final ObjectMapper objectMapper;
    private final JwtProperties properties;

    @Override
    public void commence(
            HttpServletRequest request,
            HttpServletResponse response,
            AuthenticationException exception
    ) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        PrintWriter writer = response.getWriter();
        objectMapper.writeValue(
                writer,
                new ErrorResponse(errorCode(request))
        );
    }

    private AuthErrorCode errorCode(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return AuthErrorCode.UNAUTHENTICATED;
        }
        String configuredName = properties.getCookieName();
        for (Cookie cookie : cookies) {
            String name = cookie.getName();
            if (configuredName.equals(name)) {
                return AuthErrorCode.INVALID_JWT;
            }
        }
        return AuthErrorCode.UNAUTHENTICATED;
    }
}
