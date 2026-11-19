package com.knot.backend.search.presentation;

import static com.knot.backend.global.config.OpenApiConfig.ACCESS_TOKEN_COOKIE;

import com.knot.backend.auth.domain.model.AuthenticatedMember;
import com.knot.backend.global.response.ErrorResponse;
import com.knot.backend.search.presentation.dto.request.SearchNotionAuthorizationRequest;
import com.knot.backend.search.presentation.dto.response.SearchNotionAuthorizationResponse;
import com.knot.backend.search.presentation.dto.response.SearchNotionConnectionResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

@Tag(name = "탐색 노션 연결", description = "Workspace 노션 연결 조회, 연결 시작, 끊기와 노션 동의 화면 콜백")
public interface SearchNotionConnectionApi {

    @SecurityRequirement(name = ACCESS_TOKEN_COOKIE)
    @Operation(summary = "노션 연결 조회", description = "현재 Workspace 멤버면 OWNER와 MEMBER 구분 없이 조회합니다. "
            + "연결이 없어도 200이고 status가 NOT_CONNECTED입니다. 만료된 연결은 EXPIRED로 줍니다. 토큰과 노션 워크스페이스 ID는 싣지 않습니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = SearchNotionConnectionResponse.class))),
            @ApiResponse(responseCode = "401", description = "UNAUTHENTICATED: 로그인하지 않음", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "WORKSPACE_ACCESS_DENIED: 현재 Workspace 멤버가 아님", content = @Content(schema = @Schema(implementation = ErrorResponse.class)))})
    SearchNotionConnectionResponse find(
            @Parameter(description = "Workspace ID") Long workspaceId,
            @Parameter(hidden = true) AuthenticatedMember authenticatedMember
    );

    @SecurityRequirement(name = ACCESS_TOKEN_COOKIE)
    @Operation(summary = "노션 연결 시작", description = "연결이 없을 때와 만료일 때만 노션 동의 화면 주소를 줍니다. "
            + "연결됨이면 409로 거절하고 앞선 연결을 바꾸지 않습니다. 화면은 받은 주소로 브라우저를 이동시킵니다. 동의 화면을 마치면 노션이 콜백으로 돌려보냅니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "노션 동의 화면 주소", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = SearchNotionAuthorizationResponse.class))),
            @ApiResponse(responseCode = "400", description = "VALIDATION_ERROR: agreed가 없거나 true가 아님", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "UNAUTHENTICATED: 로그인하지 않음", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "WORKSPACE_ACCESS_DENIED: 현재 Workspace 멤버가 아님", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "409", description = "NOTION_CONNECTION_ALREADY_CONNECTED: 이미 연결됨", content = @Content(schema = @Schema(implementation = ErrorResponse.class)))})
    SearchNotionAuthorizationResponse startAuthorization(
            @Parameter(description = "Workspace ID") Long workspaceId,
            SearchNotionAuthorizationRequest request,
            @Parameter(hidden = true) AuthenticatedMember authenticatedMember
    );

    @SecurityRequirement(name = ACCESS_TOKEN_COOKIE)
    @Operation(summary = "노션 연결 끊기", description = "연결한 사람과 Workspace OWNER만 끊습니다. 만료된 연결도 끊습니다. "
            + "만든 페이지 이력, 지난 답변과 근거, 대기인 쓰기 확인 카드는 지우지 않습니다.")
    @ApiResponses({@ApiResponse(responseCode = "204", description = "끊기 성공"),
            @ApiResponse(responseCode = "401", description = "UNAUTHENTICATED: 로그인하지 않음", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "WORKSPACE_ACCESS_DENIED: 현재 Workspace 멤버가 아님 / NOTION_CONNECTION_DISCONNECT_DENIED: 연결한 사람도 OWNER도 아님", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "NOTION_CONNECTION_NOT_FOUND: 끊을 노션 연결이 없음", content = @Content(schema = @Schema(implementation = ErrorResponse.class)))})
    void disconnect(
            @Parameter(description = "Workspace ID") Long workspaceId,
            @Parameter(hidden = true) AuthenticatedMember authenticatedMember
    );

    @SecurityRequirements
    @Operation(summary = "노션 동의 화면 콜백", description = "노션이 브라우저를 돌려보내는 주소입니다. 화면 코드는 부르지 않습니다. "
            + "로그인 쿠키 없이 state 하나로 처리하고 연결된 앱 화면으로 302를 보냅니다. 결과는 notionConnection 쿼리 하나로 전합니다"
            + "(CONNECTED, NOTION_CONNECTION_ALREADY_CONNECTED, CANCELLED, FAILED). state를 찾지 못하면 쿼리 없이 기본 화면으로 보냅니다.")
    @ApiResponses({@ApiResponse(responseCode = "302", description = "연결된 앱 화면으로 이동")})
    ResponseEntity<Void> callback(
            @Parameter(description = "연결 시작 때 보낸 state") String state,
            @Parameter(description = "노션이 준 authorization code") String code,
            @Parameter(description = "노션이 준 OAuth 오류 값(access_denied 등)") String error,
            @Parameter(description = "노션 authorization server issuer") String iss
    );
}
