package com.knot.backend.workspace.presentation;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.knot.backend.auth.domain.model.AuthenticatedMember;
import com.knot.backend.global.exception.GlobalExceptionHandler;
import com.knot.backend.workspace.application.WorkspaceLeaveService;
import com.knot.backend.workspace.application.WorkspaceService;
import com.knot.backend.workspace.domain.WorkspaceErrorCode;
import com.knot.backend.workspace.domain.WorkspaceException;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class WorkspaceControllerTest {
    private WorkspaceService workspaceService;
    private WorkspaceLeaveService workspaceLeaveService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        workspaceService = mock(WorkspaceService.class);
        workspaceLeaveService = mock(WorkspaceLeaveService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(
                new WorkspaceController(
                        workspaceService,
                        workspaceLeaveService
                )
        )
                .setControllerAdvice(new GlobalExceptionHandler())
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .build();
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("인증된 member가 워크스페이스를 생성하면 201과 생성된 ID를 반환한다")
    void create_success() throws Exception {
        // given
        SecurityContextHolder.getContext()
                .setAuthentication(memberAuthentication());
        when(
                workspaceService.create(
                        1L,
                        "Knot 팀"
                )
        ).thenReturn(7L);

        // when
        ResultActions result = mockMvc.perform(
                post("/api/v1/workspaces").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Knot 팀"}
                                """)
        );

        // then
        result.andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(7L));
        verify(workspaceService).create(
                1L,
                "Knot 팀"
        );
    }

    @Test
    @DisplayName("워크스페이스 이름 규칙을 위반하면 INVALID_WORKSPACE_NAME 400 응답을 반환한다")
    void create_failure_invalidWorkspaceName() throws Exception {
        // given
        SecurityContextHolder.getContext()
                .setAuthentication(memberAuthentication());
        when(
                workspaceService.create(
                        1L,
                        "Knot!"
                )
        ).thenThrow(new WorkspaceException(WorkspaceErrorCode.INVALID_WORKSPACE_NAME));

        // when
        ResultActions result = mockMvc.perform(
                post("/api/v1/workspaces").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Knot!"}
                                """)
        );

        // then
        result.andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_WORKSPACE_NAME"))
                .andExpect(jsonPath("$.message").value("워크스페이스 이름이 올바르지 않습니다"));
    }

    @Test
    @DisplayName("인증된 member가 본인 워크스페이스 멤버십을 탈퇴하면 204를 반환한다")
    void leave_success() throws Exception {
        // given
        SecurityContextHolder.getContext()
                .setAuthentication(memberAuthentication());

        // when
        ResultActions result = mockMvc.perform(delete("/api/v1/workspaces/7/members/me"));

        // then
        result.andExpect(status().isNoContent());
        verify(workspaceLeaveService).leave(
                1L,
                7L
        );
    }

    @Test
    @DisplayName("다른 활성 멤버가 있는 OWNER가 일반 탈퇴하면 WORKSPACE_OWNER_TRANSFER_REQUIRED 409 응답을 반환한다")
    void leave_failure_ownerTransferRequired() throws Exception {
        // given
        SecurityContextHolder.getContext()
                .setAuthentication(memberAuthentication());
        org.mockito.Mockito.doThrow(new WorkspaceException(WorkspaceErrorCode.WORKSPACE_OWNER_TRANSFER_REQUIRED))
                .when(workspaceLeaveService)
                .leave(
                        1L,
                        7L
                );

        // when
        ResultActions result = mockMvc.perform(delete("/api/v1/workspaces/7/members/me"));

        // then
        result.andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("WORKSPACE_OWNER_TRANSFER_REQUIRED"))
                .andExpect(jsonPath("$.message").value("OWNER 권한을 승계하거나 워크스페이스를 삭제해야 합니다"));
    }

    private UsernamePasswordAuthenticationToken memberAuthentication() {
        return new UsernamePasswordAuthenticationToken(
                AuthenticatedMember.of(
                        1L,
                        "octocat",
                        null
                ),
                null,
                List.of(new SimpleGrantedAuthority("ROLE_USER"))
        );
    }
}
