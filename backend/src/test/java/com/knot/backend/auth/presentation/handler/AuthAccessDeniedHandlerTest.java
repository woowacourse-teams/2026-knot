package com.knot.backend.auth.presentation.handler;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.csrf.MissingCsrfTokenException;
import tools.jackson.databind.ObjectMapper;

class AuthAccessDeniedHandlerTest {

    private final AuthAccessDeniedHandler handler = new AuthAccessDeniedHandler(new ObjectMapper());

    @Test
    @DisplayName("요약 재시도 경로의 CSRF 오류만 명세의 전용 코드로 응답한다")
    void handle_success_summaryCsrf() throws Exception {
        // given
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI("/api/v1/workspaces/1/recordings/2/meeting-summary/retry");
        MockHttpServletResponse response = new MockHttpServletResponse();
        // when
        handler.handle(
                request,
                response,
                new MissingCsrfTokenException("test")
        );
        // then
        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString()).contains("CSRF_INVALID");
    }

    @Test
    @DisplayName("기존 녹음 API와 일반 접근 거절은 FORBIDDEN을 유지한다")
    void handle_success_preservesExistingContract() throws Exception {
        // given
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI("/api/v1/workspaces/1/recordings/2/transcription/retry");
        MockHttpServletResponse response = new MockHttpServletResponse();
        // when
        handler.handle(
                request,
                response,
                new MissingCsrfTokenException("test")
        );
        // then
        assertThat(response.getContentAsString()).contains("FORBIDDEN");
    }

    @Test
    @DisplayName("요약 경로라도 CSRF 이외의 접근 거절은 전용 CSRF 코드로 바꾸지 않는다")
    void handle_success_nonCsrfDenied() throws Exception {
        // given
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI("/api/v1/workspaces/1/recordings/2/meeting-summary/retry");
        MockHttpServletResponse response = new MockHttpServletResponse();
        // when
        handler.handle(
                request,
                response,
                new AccessDeniedException("denied")
        );
        // then
        assertThat(response.getContentAsString()).contains("FORBIDDEN");
    }
}
