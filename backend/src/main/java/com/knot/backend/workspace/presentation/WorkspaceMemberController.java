package com.knot.backend.workspace.presentation;
import com.knot.backend.auth.domain.model.AuthenticatedMember;
import com.knot.backend.workspace.application.WorkspaceMemberService;
import com.knot.backend.workspace.application.dto.result.WorkspaceMemberPage;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import static com.knot.backend.global.config.OpenApiConfig.ACCESS_TOKEN_COOKIE;
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/workspaces")
@SecurityRequirement(name = ACCESS_TOKEN_COOKIE)
public class WorkspaceMemberController {
    private final WorkspaceMemberService members;
    @GetMapping("/{workspaceId}/members")
    @Operation(summary = "워크스페이스 멤버 목록", description = "현재 멤버를 가입 시각·멤버 ID 오름차순으로 조회한다. 본인 포함, OWNER/MEMBER 모두 조회 가능. 비멤버·없는 워크스페이스는 404, limit 범위 밖은 INVALID_LIMIT(400).")
    public WorkspaceMemberPage find(
            @PathVariable long workspaceId,
            @AuthenticationPrincipal @Parameter(hidden = true) AuthenticatedMember member,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "50") int limit
    ) {
        return members.find(
                workspaceId,
                member.getMemberId(),
                cursor,
                limit
        );
    }
}
