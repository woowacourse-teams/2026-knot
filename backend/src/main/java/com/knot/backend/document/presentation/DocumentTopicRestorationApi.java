package com.knot.backend.document.presentation;

import static com.knot.backend.global.config.OpenApiConfig.ACCESS_TOKEN_COOKIE;
import static com.knot.backend.global.config.OpenApiConfig.CSRF_TOKEN_HEADER_NAME;

import com.knot.backend.auth.domain.model.AuthenticatedMember;
import com.knot.backend.document.presentation.dto.request.DocumentTopicRestorationRequest;
import com.knot.backend.document.presentation.dto.response.DocumentTopicRestorationResponse;
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
public interface DocumentTopicRestorationApi {

    @Operation(summary = "직전 주제 정리 되돌리기 시작", description = "현재 OWNER가 직전 재정리로 변경된 문서의 이전 배정을 복원하도록 접수합니다. "
            + "문서 내용·안건·확인 상태와 이후 생성된 문서는 유지합니다. 새 접수202·같은 requestId와 versionId 재전송200이며, 성공으로 복원 정보가 소진된 후에도 같은 작업을 반환합니다. "
            + "복원 성공과 복원 정보 소진은 함께 확정합니다. 실패·취소 시 현재 분류와 복원 기회를 유지합니다. "
            + "status는 시작·조회·취소와 같은7개 상태를 사용합니다. 실제 완료는 기존 상태 조회로 확인합니다.", parameters = @Parameter(name = CSRF_TOKEN_HEADER_NAME, in = ParameterIn.HEADER, required = true, description = "XSRF-TOKEN 쿠키와 일치하는 CSRF 토큰", schema = @Schema(type = "string")))
    @ApiResponses({
            @ApiResponse(responseCode = "202", description = "새 작업 접수 완료", content = @Content(schema = @Schema(implementation = DocumentTopicRestorationResponse.class))),
            @ApiResponse(responseCode = "200", description = "같은 요청의 기존 작업 반환", content = @Content(schema = @Schema(implementation = DocumentTopicRestorationResponse.class))),
            @ApiResponse(responseCode = "400", description = "VALIDATION_ERROR: requestId 누락·UUID 형식·양수 범위 오류, INVALID_REQUEST_BODY: JSON 오류, INVALID_PARAMETER: 경로 타입 오류", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "UNAUTHENTICATED: 인증 필요", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "WORKSPACE_ACCESS_DENIED, WORKSPACE_OWNER_REQUIRED 또는 FORBIDDEN: 접근·OWNER·CSRF 검증 실패", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "WORKSPACE_NOT_FOUND: 없거나 삭제된 Workspace", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "409", description = "TOPIC_OPERATION_IN_PROGRESS: 다른 작업 진행 중, TOPIC_REQUEST_ID_CONFLICT: 요청 식별자 충돌, TOPIC_RESTORATION_NOT_AVAILABLE: 복원본 없음·소진, TOPIC_RESTORATION_VERSION_CONFLICT: 현재 버전 변경", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "500", description = "INTERNAL_SERVER_ERROR: 요청 처리 오류. 같은 requestId 재전송 또는 상태 조회로 접수 여부를 확인합니다", content = @Content(schema = @Schema(implementation = ErrorResponse.class)))})
    ResponseEntity<DocumentTopicRestorationResponse> start(
            @Parameter(description = "Workspace ID") @Positive long workspaceId,
            @Parameter(hidden = true) AuthenticatedMember member,
            @Valid DocumentTopicRestorationRequest request
    );
}
