package com.knot.backend.document.presentation;

import static com.knot.backend.global.config.OpenApiConfig.ACCESS_TOKEN_COOKIE;
import static com.knot.backend.global.config.OpenApiConfig.CSRF_TOKEN_HEADER_NAME;

import com.knot.backend.auth.domain.model.AuthenticatedMember;
import com.knot.backend.document.presentation.dto.request.DocumentTopicReorganizationRequest;
import com.knot.backend.document.presentation.dto.response.DocumentTopicOperationResponse;
import com.knot.backend.global.response.ErrorResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import org.springframework.http.ResponseEntity;

@Tag(name = "문서 주제 정리", description = "기존 문서의 큰 주제 재정리")
@SecurityRequirement(name = ACCESS_TOKEN_COOKIE)
public interface DocumentTopicReorganizationApi {

    @Operation(summary = "큰 주제 재정리 시작", description = "현재 OWNER가 시작 시점의 DRAFT·ARCHIVED 문서를 접수합니다. "
            + "제목·본문·안건·확인 상태는 보존하고, 검증된 전체 배정만 한 번에 적용합니다. "
            + "작업 중 생성된 문서는 제외합니다. 같은 requestId는 Workspace가 존재하는 동안 같은 작업을 반환합니다. "
            + "처리 실패·30분 초과 시 기존 분류와 직전 복원 정보는 유지합니다.", parameters = @Parameter(name = CSRF_TOKEN_HEADER_NAME, in = ParameterIn.HEADER, required = true, description = "XSRF-TOKEN 쿠키와 일치하는 CSRF 토큰", schema = @Schema(type = "string")))
    @ApiResponses({
            @ApiResponse(responseCode = "202", description = "새 작업 접수 완료", content = @Content(schema = @Schema(implementation = DocumentTopicOperationResponse.class))),
            @ApiResponse(responseCode = "200", description = "같은 요청의 기존 작업 반환", content = @Content(schema = @Schema(implementation = DocumentTopicOperationResponse.class))),
            @ApiResponse(responseCode = "400", description = "INVALID_PARAMETER: UUID 누락·형식 또는 경로 오류", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "UNAUTHENTICATED: 인증 필요", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "WORKSPACE_ACCESS_DENIED, WORKSPACE_OWNER_REQUIRED 또는 FORBIDDEN: 접근·OWNER·CSRF 검증 실패", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "409", description = "TOPIC_OPERATION_CONFLICT: 다른 정리·복원 작업 진행 중 또는 다른 종류에 사용된 requestId", content = @Content(schema = @Schema(implementation = ErrorResponse.class)))})
    ResponseEntity<DocumentTopicOperationResponse> start(
            @Parameter(description = "Workspace ID") @Positive long workspaceId,
            @Parameter(hidden = true) AuthenticatedMember member,
            @Valid DocumentTopicReorganizationRequest request
    );
}
