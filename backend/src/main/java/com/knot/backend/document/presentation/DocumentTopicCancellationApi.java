package com.knot.backend.document.presentation;

import static com.knot.backend.global.config.OpenApiConfig.ACCESS_TOKEN_COOKIE;
import static com.knot.backend.global.config.OpenApiConfig.CSRF_TOKEN_HEADER_NAME;

import com.knot.backend.auth.domain.model.AuthenticatedMember;
import com.knot.backend.document.presentation.dto.response.DocumentTopicCancellationResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Positive;
import org.springframework.http.ResponseEntity;

@Tag(name = "문서 주제 정리")
@SecurityRequirement(name = ACCESS_TOKEN_COOKIE)
public interface DocumentTopicCancellationApi {

    @Operation(summary = "진행 중 주제 정리 작업 취소", description = "현재 OWNER가 QUEUED/RUNNING 작업의 취소를 접수합니다. "
            + "본문과 requestId는 없습니다. CANCEL_REQUESTED 재요청은 202, CANCELLED 재요청은 200입니다. "
            + "최초 cancelRequestedAt은 유지합니다. APPLYING/SUCCEEDED/FAILED는 409입니다. "
            + "202 뒤 기존 상태 조회로 확인하며 취소 완료 시 목록을 갱신하지 않습니다. 늦은 AI 결과는 적용하지 않습니다.", parameters = @Parameter(name = CSRF_TOKEN_HEADER_NAME, in = ParameterIn.HEADER, required = true, description = "XSRF-TOKEN 쿠키에 대응하는 토큰", schema = @Schema(type = "string")))
    @ApiResponses({@ApiResponse(responseCode = "202", description = "취소 접수·처리 중"),
            @ApiResponse(responseCode = "200", description = "취소 완료·동일 작업 재요청"),
            @ApiResponse(responseCode = "400", description = "VALIDATION_ERROR 또는 INVALID_PARAMETER"),
            @ApiResponse(responseCode = "401", description = "UNAUTHENTICATED 또는 INVALID_JWT"),
            @ApiResponse(responseCode = "403", description = "WORKSPACE_ACCESS_DENIED, WORKSPACE_OWNER_REQUIRED 또는 FORBIDDEN"),
            @ApiResponse(responseCode = "404", description = "WORKSPACE_NOT_FOUND 또는 TOPIC_OPERATION_NOT_FOUND"),
            @ApiResponse(responseCode = "409", description = "TOPIC_OPERATION_NOT_CANCELLABLE"),
            @ApiResponse(responseCode = "500", description = "INTERNAL_SERVER_ERROR: 동일 작업 재요청·상태 조회 필요")})
    ResponseEntity<DocumentTopicCancellationResponse> cancel(
            @Positive long workspaceId,
            @Positive long operationId,
            @Parameter(hidden = true) AuthenticatedMember member
    );
}
