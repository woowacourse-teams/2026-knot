package com.knot.backend.global.exception;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.matchesPattern;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.knot.backend.auth.domain.AuthTokenProvider;
import com.knot.backend.auth.domain.model.AuthenticatedMember;
import com.knot.backend.auth.infrastructure.github.GithubOAuth2UserService;
import com.knot.backend.auth.infrastructure.jwt.JwtAuthenticationFilter;
import com.knot.backend.auth.presentation.handler.AuthAccessDeniedHandler;
import com.knot.backend.auth.presentation.handler.AuthAuthenticationEntryPoint;
import com.knot.backend.auth.presentation.handler.AuthLogoutSuccessHandler;
import com.knot.backend.auth.presentation.handler.JwtLogoutHandler;
import com.knot.backend.auth.presentation.handler.OAuth2AuthenticationFailureHandler;
import com.knot.backend.auth.presentation.handler.OAuth2AuthenticationSuccessHandler;
import com.knot.backend.global.config.SecurityConfig;
import com.knot.backend.global.logging.RequestIdFilter;
import com.knot.backend.testsupport.LogCapture;
import org.slf4j.MDC;
import com.knot.backend.search.application.SearchAnswerRetryService;
import com.knot.backend.search.application.SearchQuestionIntakeService;
import com.knot.backend.search.presentation.SearchAnswerEventsController;
import com.knot.backend.search.presentation.SearchQuestionController;
import com.knot.backend.search.presentation.stream.SearchAnswerStream;
import jakarta.servlet.http.Cookie;
import com.knot.backend.document.presentation.DocumentTopicAuthenticationEntryPoint;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.CsrfRequestPostProcessor;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.junit.jupiter.web.SpringJUnitWebConfig;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@SpringJUnitWebConfig({HttpErrorWebMvcConfiguration.class, SecurityConfig.class, JwtAuthenticationFilter.class,
        AuthAuthenticationEntryPoint.class, DocumentTopicAuthenticationEntryPoint.class, AuthAccessDeniedHandler.class,
        AuthLogoutSuccessHandler.class, GlobalExceptionHandler.class, SearchQuestionExceptionHandler.class,
        SearchQuestionController.class, SearchAnswerEventsController.class})
@TestPropertySource(properties = {"auth.jwt.cookie-name=KNOT_ACCESS_TOKEN", "auth.jwt.secure=false",
        "auth.cors.allowed-origins=https://knoted.kr"})
class GlobalExceptionHandlerSecurityMvcTest {
    private static final String SEARCH_PATH = "/api/v1/workspaces/1/search/conversations";

    @Autowired
    private WebApplicationContext context;
    @MockitoBean
    private AuthTokenProvider tokens;
    @MockitoBean
    private GithubOAuth2UserService users;
    @MockitoBean
    private OAuth2AuthenticationSuccessHandler successHandler;
    @MockitoBean
    private OAuth2AuthenticationFailureHandler failureHandler;
    @MockitoBean
    private JwtLogoutHandler logoutHandler;
    @MockitoBean
    private SearchQuestionIntakeService intake;
    @MockitoBean
    private SearchAnswerRetryService retries;
    @MockitoBean
    private SearchAnswerStream streams;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        when(tokens.authenticate("test-access")).thenReturn(
                AuthenticatedMember.of(
                        1L,
                        "member",
                        null
                )
        );
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(new RequestIdFilter())
                .apply(springSecurity())
                .build();
    }

    @Test
    @DisplayName("실제 JWT 필터 인증을 통과한 정상 GET은 성공한다")
    void read_success_authenticated() throws Exception {
        // given
        MockHttpServletRequestBuilder request = get("/api/v1/test/http-errors/ok");
        request.cookie(accessCookie());

        // when & then
        mvc.perform(request)
                .andExpect(status().isOk())
                .andExpect(
                        header().string(
                                "X-Request-ID",
                                matchesPattern("[0-9a-f]{32}")
                        )
                )
                .andExpect(content().string("ok"));
    }

    @Test
    @DisplayName("실제 JWT와 CSRF 필터를 통과한 JSON 요청은 성공한다")
    void create_success_authenticatedWithCsrf() throws Exception {
        // given
        MockHttpServletRequestBuilder request = post("/api/v1/test/http-errors/json");
        request.cookie(accessCookie());
        request.with(validCsrf());
        request.contentType(MediaType.APPLICATION_JSON);
        request.content("{\"value\":\"ok\"}");

        // when & then
        mvc.perform(request)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.value").value("ok"));
    }

    @Test
    @DisplayName("인증되지 않은 없는 경로 요청은 MVC 전에 기존 401로 거절된다")
    void read_failure_unauthenticated() throws Exception {
        // given
        MockHttpServletRequestBuilder request = get("/api/v1/test/http-errors/missing");

        // when & then
        mvc.perform(request)
                .andExpect(status().isUnauthorized())
                .andExpect(
                        header().string(
                                "X-Request-ID",
                                matchesPattern("[0-9a-f]{32}")
                        )
                )
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    @DisplayName("CSRF가 없는 변경 요청은 미디어 오류보다 먼저 기존 403으로 거절된다")
    void create_failure_missingCsrf() throws Exception {
        // given
        MockHttpServletRequestBuilder request = post("/api/v1/test/http-errors/json");
        request.cookie(accessCookie());
        request.contentType(MediaType.TEXT_PLAIN);
        request.content("ok");

        // when & then
        mvc.perform(request)
                .andExpect(status().isForbidden())
                .andExpect(
                        header().string(
                                "X-Request-ID",
                                matchesPattern("[0-9a-f]{32}")
                        )
                )
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    @DisplayName("유효하지 않은 CSRF로 보낸 잘못된 메서드 요청도 먼저 403으로 거절된다")
    void read_failure_invalidCsrf() throws Exception {
        // given
        MockHttpServletRequestBuilder request = post("/api/v1/test/http-errors/ok");
        request.cookie(accessCookie());
        request.with(invalidCsrf());

        // when & then
        mvc.perform(request)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"application/json", "text/event-stream"})
    @DisplayName("인증 후 MVC handler가 없는 경로는 공통 JSON 404를 반환한다")
    void read_failure_noHandler(String accept) throws Exception {
        // given
        MockHttpServletRequestBuilder request = get("/api/v1/test/http-errors/missing");
        request.cookie(accessCookie());
        request.accept(accept);

        // when & then
        mvc.perform(request)
                .andExpect(status().isNotFound())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("REQUEST_PATH_NOT_FOUND"))
                .andExpect(
                        result -> assertThat(result.getResolvedException()).isInstanceOf(NoHandlerFoundException.class)
                );
    }

    @ParameterizedTest
    @ValueSource(strings = {"application/json", "text/event-stream"})
    @DisplayName("인증 후 정적 리소스가 없는 경로는 공통 JSON 404를 반환한다")
    void read_failure_noResource(String accept) throws Exception {
        // given
        MockHttpServletRequestBuilder request = get("/assets/missing.js");
        request.cookie(accessCookie());
        request.accept(accept);

        // when & then
        mvc.perform(request)
                .andExpect(status().isNotFound())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("REQUEST_PATH_NOT_FOUND"))
                .andExpect(
                        result -> assertThat(result.getResolvedException()).isInstanceOf(NoResourceFoundException.class)
                );
    }

    @ParameterizedTest
    @ValueSource(strings = {"application/json", "text/event-stream"})
    @DisplayName("인증과 CSRF 통과 후 잘못된 메서드는 405와 Allow를 반환한다")
    void read_failure_methodNotAllowed(String accept) throws Exception {
        // given
        MockHttpServletRequestBuilder request = post("/api/v1/test/http-errors/ok");
        request.cookie(accessCookie());
        request.with(validCsrf());
        request.accept(accept);

        // when & then
        mvc.perform(request)
                .andExpect(status().isMethodNotAllowed())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(
                        header().string(
                                HttpHeaders.ALLOW,
                                "GET"
                        )
                )
                .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"application/json", "text/event-stream"})
    @DisplayName("인증과 CSRF 통과 후 잘못된 Content-Type은 415와 Accept를 반환한다")
    void create_failure_unsupportedMediaType(String accept) throws Exception {
        // given
        MockHttpServletRequestBuilder request = post("/api/v1/test/http-errors/json");
        request.cookie(accessCookie());
        request.with(validCsrf());
        request.contentType(MediaType.TEXT_PLAIN);
        request.content("ok");
        request.accept(accept);

        // when & then
        mvc.perform(request)
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(
                        header().string(
                                HttpHeaders.ACCEPT,
                                MediaType.APPLICATION_JSON_VALUE
                        )
                )
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_TYPE"));
    }

    @Test
    @DisplayName("탐색 전용 advice가 있어도 매핑 메서드 오류는 405 JSON을 반환한다")
    void search_failure_methodNotAllowed() throws Exception {
        // given
        MockHttpServletRequestBuilder request = get(SEARCH_PATH);
        request.cookie(accessCookie());
        request.accept(MediaType.TEXT_EVENT_STREAM);

        // when & then
        mvc.perform(request)
                .andExpect(status().isMethodNotAllowed())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(
                        header().string(
                                HttpHeaders.ALLOW,
                                "POST"
                        )
                )
                .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"));
        verifyNoInteractions(
                intake,
                retries,
                streams
        );
    }

    @Test
    @DisplayName("탐색 전용 advice가 있어도 Content-Type 오류는 415 JSON을 반환한다")
    void search_failure_unsupportedMediaType() throws Exception {
        // given
        MockHttpServletRequestBuilder request = post(SEARCH_PATH);
        request.cookie(accessCookie());
        request.with(validCsrf());
        request.contentType(MediaType.TEXT_PLAIN);
        request.content("question");
        request.accept(MediaType.TEXT_EVENT_STREAM);

        // when & then
        mvc.perform(request)
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(
                        header().string(
                                HttpHeaders.ACCEPT,
                                MediaType.APPLICATION_JSON_VALUE
                        )
                )
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_TYPE"));
        verifyNoInteractions(
                intake,
                retries,
                streams
        );
    }

    @Test
    @DisplayName("인증·CSRF 통과 후 500 오류의 응답과 안전한 오류 로그에 같은 ID를 기록한다")
    void search_failure_preservesRequestIdOnServerError() throws Exception {
        // given
        String requestId = "0123456789abcdef0123456789abcdef";
        when(streams.reserve()).thenThrow(new IllegalStateException("private-error"));
        MockHttpServletRequestBuilder request = post(SEARCH_PATH);
        request.cookie(accessCookie());
        request.with(validCsrf());
        request.header(
                "X-Request-ID",
                requestId
        );
        request.contentType(MediaType.APPLICATION_JSON);
        request.accept(MediaType.TEXT_EVENT_STREAM);
        request.content("{\"content\":\"test\",\"requestId\":\"aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee\"}");

        try (LogCapture logs = new LogCapture(
                SearchQuestionExceptionHandler.class,
                GlobalExceptionHandler.class
        )) {
            // when & then
            mvc.perform(request)
                    .andExpect(status().isInternalServerError())
                    .andExpect(
                            header().string(
                                    "X-Request-ID",
                                    requestId
                            )
                    )
                    .andExpect(content().contentType(MediaType.APPLICATION_JSON));
            assertThat(logs.events()).singleElement()
                    .satisfies(event -> {
                        assertThat(event.getMDCPropertyMap()).containsEntry(
                                "requestId",
                                requestId
                        );
                        assertThat(event.getFormattedMessage()).doesNotContain("private-error");
                        assertThat(event.getThrowableProxy()).isNull();
                    });
            assertThat(MDC.get("requestId")).isNull();
        }
    }

    private RequestPostProcessor validCsrf() {
        CsrfRequestPostProcessor token = csrf();
        return token.asHeader();
    }

    private RequestPostProcessor invalidCsrf() {
        CsrfRequestPostProcessor token = csrf();
        token.useInvalidToken();
        return token.asHeader();
    }

    private Cookie accessCookie() {
        return new Cookie(
                "KNOT_ACCESS_TOKEN",
                "test-access"
        );
    }
}
