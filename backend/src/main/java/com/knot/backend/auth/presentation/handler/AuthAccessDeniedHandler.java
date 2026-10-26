package com.knot.backend.auth.presentation.handler;

import com.knot.backend.auth.domain.AuthErrorCode;
import com.knot.backend.global.exception.CommonErrorCode;
import com.knot.backend.global.exception.ErrorCode;
import com.knot.backend.global.response.ErrorResponse;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.csrf.CsrfException;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
@RequiredArgsConstructor
public class AuthAccessDeniedHandler implements AccessDeniedHandler {
    private static final Pattern MEETING_SUMMARY_RETRY = Pattern
            .compile("^/api/v1/workspaces/[^/]+/recordings/[^/]+/meeting-summary/retry$");
    private static final String NICKNAME_SETUP_PATH = "/api/v1/auth/nickname";
    private static final String TOKEN_REFRESH_PATH = "/api/v1/auth/refresh";
    private static final String LOGOUT_PATH = "/api/v1/auth/logout";

    private final ObjectMapper objectMapper;

    @Override
    public void handle(
            HttpServletRequest request,
            HttpServletResponse response,
            AccessDeniedException accessDeniedException
    ) throws IOException, ServletException {
        ErrorCode errorCode = resolveErrorCode(
                request.getRequestURI(),
                accessDeniedException
        );
        writeErrorResponse(
                response,
                errorCode
        );
    }

    private ErrorCode resolveErrorCode(
            String uri,
            AccessDeniedException exception
    ) {
        if (isAuthRequest(uri)) {
            return AuthErrorCode.CSRF_INVALID;
        }
        if (CsrfException.class.isInstance(exception) && MEETING_SUMMARY_RETRY.matcher(uri)
                .matches()) {
            return AuthErrorCode.CSRF_INVALID;
        }
        return CommonErrorCode.FORBIDDEN;
    }

    private boolean isAuthRequest(String requestUri) {
        return requestUri.endsWith(NICKNAME_SETUP_PATH) || requestUri.endsWith(TOKEN_REFRESH_PATH)
                || requestUri.endsWith(LOGOUT_PATH);
    }

    private void writeErrorResponse(
            HttpServletResponse response,
            ErrorCode errorCode
    ) throws IOException {
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(
                response.getWriter(),
                new ErrorResponse(errorCode)
        );
    }
}
