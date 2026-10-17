package com.knot.backend.document.presentation;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.knot.backend.auth.domain.model.AuthenticatedMember;
import com.knot.backend.document.application.DocumentConfirmationService;
import com.knot.backend.document.application.DocumentConfirmationCommandService;
import com.knot.backend.document.application.DocumentDetailService;
import com.knot.backend.document.application.DocumentTranscriptService;
import com.knot.backend.document.application.DocumentListService;
import com.knot.backend.document.application.dto.result.DocumentConfirmationSummaryResult;
import com.knot.backend.document.application.dto.result.DocumentDetailResult;
import com.knot.backend.document.domain.DocumentErrorCode;
import com.knot.backend.document.domain.DocumentException;
import com.knot.backend.document.domain.model.DocumentStatus;
import com.knot.backend.document.domain.model.MyConfirmationState;
import com.knot.backend.global.exception.GlobalExceptionHandler;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class DocumentControllerTest {
    private static final String PATH = "/api/v1/workspaces/{workspaceId}/documents/{documentId}";
    private DocumentDetailService service;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        service = mock(DocumentDetailService.class);
        mvc = MockMvcBuilders.standaloneSetup(
                new DocumentController(
                        mock(DocumentTranscriptService.class),
                        service,
                        mock(DocumentListService.class),
                        mock(DocumentConfirmationService.class),
                        mock(DocumentConfirmationCommandService.class)
                )
        )
                .setControllerAdvice(new GlobalExceptionHandler())
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .build();
        SecurityContextHolder.getContext()
                .setAuthentication(
                        new UsernamePasswordAuthenticationToken(
                                AuthenticatedMember.of(
                                        2,
                                        "멤버",
                                        null
                                ),
                                null,
                                List.of(new SimpleGrantedAuthority("ROLE_USER"))
                        )
                );
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("경로와 로그인 멤버 ID를 전달해 상세 응답을 반환한다")
    void findDocument_success() throws Exception {
        // given
        when(
                service.find(
                        1,
                        2,
                        3
                )
        ).thenReturn(
                new DocumentDetailResult(
                        3,
                        4,
                        "주제",
                        "제목",
                        null,
                        "본문",
                        DocumentStatus.DRAFT,
                        Instant.parse("2026-10-06T00:00:00Z"),
                        null,
                        10,
                        5,
                        MyConfirmationState.PENDING,
                        new DocumentConfirmationSummaryResult(
                                0,
                                1,
                                0
                        ),
                        null
                )
        );

        // when & then
        mvc.perform(
                get(
                        PATH,
                        1,
                        3
                )
        )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(3))
                .andExpect(jsonPath("$.recordingSessionId").value(4))
                .andExpect(jsonPath("$.myConfirmationState").value("PENDING"));
    }

    @Test
    @DisplayName("문서 없음 예외는 기존 처리기로 404 응답을 반환한다")
    void findDocument_failure_missingDocument() throws Exception {
        // given
        when(
                service.find(
                        1,
                        2,
                        3
                )
        ).thenThrow(new DocumentException(DocumentErrorCode.DOCUMENT_NOT_FOUND));

        // when & then
        mvc.perform(
                get(
                        PATH,
                        1,
                        3
                )
        )
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("DOCUMENT_NOT_FOUND"));
    }

    @Test
    @DisplayName("숫자 형식 오류는 서비스를 호출하지 않는다")
    void findDocument_failure_invalidId() throws Exception {
        // when & then
        mvc.perform(
                get(
                        PATH,
                        1,
                        "invalid"
                )
        )
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"));
        verifyNoInteractions(service);
    }
}
