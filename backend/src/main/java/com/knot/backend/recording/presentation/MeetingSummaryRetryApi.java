package com.knot.backend.recording.presentation;

import static com.knot.backend.global.config.OpenApiConfig.ACCESS_TOKEN_COOKIE;

import com.knot.backend.auth.domain.model.AuthenticatedMember;
import com.knot.backend.global.config.OpenApiConfig;
import com.knot.backend.global.response.ErrorResponse;
import com.knot.backend.recording.presentation.dto.response.MeetingSummaryRetryResponse;
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

@Tag(name = "녹음", description = "녹음과 녹음 문서")
@SecurityRequirement(name = ACCESS_TOKEN_COOKIE)
public interface MeetingSummaryRetryApi {

    // @formatter:off
    @Operation(summary = "회의 전체 요약 다시 시도",
            description = "현재 Workspace 멤버 누구나 실패한 요약만 다시 접수한다. 횟수·기한 제한은 없다. "
                    + "FAILED이고 문서 정리에 사용한 성공 전사를 사용할 수 있어야 한다. "
                    + "주제별 문서 Job·Document·processingStatus·알림은 바꾸지 않는다. "
                    + "202·409·응답 유실 뒤 녹음 문서를 재조회하며 POST를 자동 재전송하지 않는다.",
            parameters = @Parameter(name = OpenApiConfig.CSRF_TOKEN_HEADER_NAME, in = ParameterIn.HEADER,
                    required = true, schema = @Schema(type = "string"), description = "CSRF 토큰"))
    @ApiResponses({
            @ApiResponse(responseCode = "202", description = "접수 완료. meetingSummaryStatus는 QUEUED",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = MeetingSummaryRetryResponse.class))),
            @ApiResponse(responseCode = "400", description = "INVALID_PARAMETER, INVALID_WORKSPACE_ID, INVALID_RECORDING_DATA",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "UNAUTHENTICATED: 로그인 필요",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "WORKSPACE_ACCESS_DENIED 또는 CSRF_INVALID",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "WORKSPACE_NOT_FOUND 또는 RECORDING_NOT_FOUND: 녹음 문서 접근 불가 포함",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "409", description = "MEETING_SUMMARY_RETRY_NOT_ALLOWED: 요약 없음·FAILED 아님·입력 사용 불가·중복 접수",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    MeetingSummaryRetryResponse retry(long workspaceId, long recordingId,
            @Parameter(hidden = true) AuthenticatedMember authenticatedMember);
    // @formatter:on
}
