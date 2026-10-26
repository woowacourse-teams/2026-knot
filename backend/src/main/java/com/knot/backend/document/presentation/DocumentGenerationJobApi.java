package com.knot.backend.document.presentation;

import static com.knot.backend.global.config.OpenApiConfig.ACCESS_TOKEN_COOKIE;
import static com.knot.backend.global.config.OpenApiConfig.CSRF_TOKEN_HEADER_NAME;

import com.knot.backend.auth.domain.model.AuthenticatedMember;
import com.knot.backend.document.presentation.dto.response.DocumentGenerationJobListResponse;
import com.knot.backend.document.presentation.dto.response.DocumentGenerationJobRetryResponse;
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
import org.springframework.http.MediaType;

@Tag(name = "문서 생성 작업", description = "문서 생성 진행·실패 상태 조회와 재시도 접수")
@SecurityRequirement(name = ACCESS_TOKEN_COOKIE)
public interface DocumentGenerationJobApi {

    // @formatter:off
    @Operation(summary = "내 녹음의 문서 생성 작업 목록 조회",
            description = "현재 Workspace에서 로그인한 멤버가 시작한 녹음의 QUEUED·RUNNING·마지막 실패 후 7일 표시 기한 안의 FAILED를 반환합니다. "
                    + "다른 멤버의 녹음 작업은 반환하지 않습니다. 녹음 제목 필드는 제공하지 않습니다. "
                    + "성공·표시 기한이 지난 실패 작업은 제외하며 생성 시각 내림차순, 동률이면 Job ID 내림차순입니다. 표시 기한이 지나도 Job은 삭제하지 않고 재시도할 수 있습니다. "
                    + "한 녹음의 여러 작업을 개별 반환합니다. 홈의 한 카드 선택과 종합 상태 판단은 녹음 조회 계약에서 연결합니다. "
                    + "조회는 상태를 변경하거나 자료를 삭제하지 않습니다. "
                    + "목록에서 사라진 사실만으로 녹음 전체의 완료를 판단하지 않으며 녹음 상세의 종합 결과를 확인합니다. "
                    + "페이지 요청 사이에 실행 상태와 표시 기한 경과 여부가 바뀔 수 있습니다. 요청 본문은 없습니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "생성 작업 목록 조회 성공",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = DocumentGenerationJobListResponse.class))),
            @ApiResponse(responseCode = "400", description = "INVALID_PARAMETER: 경로·size·cursor 오류 또는 커서 조회 범위 불일치",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "UNAUTHENTICATED: 로그인하지 않음",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "WORKSPACE_ACCESS_DENIED: 현재 Workspace 멤버가 아님",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    DocumentGenerationJobListResponse findDocumentGenerationJobs(
            @Parameter(description = "현재 Workspace ID") Long workspaceId,
            @Parameter(description = "같은 Workspace·Member 조회의 이전 nextCursor") String cursor,
            @Parameter(description = "생략하면 20, 허용 범위 1~100",
                    schema = @Schema(type = "integer", minimum = "1", maximum = "100", defaultValue = "20")) Integer size,
            @Parameter(hidden = true) AuthenticatedMember authenticatedMember
    );

    @Operation(summary = "실패한 문서 생성 작업 재시도",
            description = "현재 Workspace 멤버 누구나 저장 원문으로 같은 FAILED Job을 재접수합니다. "
                    + "녹음을 시작한 Member가 아니어도 됩니다. 본문은 없습니다. "
                    + "사용자 재시도 횟수와 마지막 실패 후 경과 시간 제한은 없으며 FAILED 상태와 유효 입력인 경우 허용합니다. "
                    + "Job의 QUEUED 상태와 횟수를 함께 저장한 뒤 202를 반환합니다. "
                    + "QUEUED·RUNNING·SUCCEEDED와 입력 불가는 409입니다. "
                    + "동시 요청 중 하나만 접수하며 접수 후 후속 요청은 409입니다. "
                    + "전체 시도 횟수는 최초·사용자·자동 시도를 포함하며 사용자 재시도 횟수는 기록용입니다. "
                    + "실제 AI 생성 실행은 별도 실행기가 수행하며 다른 성공 문서는 유지됩니다.",
            parameters = @Parameter(name = CSRF_TOKEN_HEADER_NAME, in = ParameterIn.HEADER, required = true,
                    description = "XSRF-TOKEN 쿠키와 일치하는 CSRF 토큰", schema = @Schema(type = "string")))
    @ApiResponses({
            @ApiResponse(responseCode = "202", description = "같은 Job 재시도 접수 완료",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = DocumentGenerationJobRetryResponse.class))),
            @ApiResponse(responseCode = "400", description = "INVALID_PARAMETER: 경로 형식·양수 범위 오류",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "UNAUTHENTICATED: 로그인하지 않음",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "WORKSPACE_ACCESS_DENIED: 현재 멤버가 아님. FORBIDDEN: CSRF 오류",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "DOCUMENT_GENERATION_JOB_NOT_FOUND: 없거나 다른 Workspace Job, 이전 정책으로 정리된 Job",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "409", description = "RETRY_NOT_ALLOWED: FAILED가 아니거나 입력을 사용할 수 없음",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    DocumentGenerationJobRetryResponse retryDocumentGenerationJob(
            @Parameter(description = "Job 원본이 속한 Workspace ID") Long workspaceId,
            @Parameter(description = "재접수할 기존 Job ID") Long jobId,
            @Parameter(hidden = true) AuthenticatedMember authenticatedMember
    );
    // @formatter:on
}
