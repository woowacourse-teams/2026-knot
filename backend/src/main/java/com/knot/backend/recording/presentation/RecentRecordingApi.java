package com.knot.backend.recording.presentation;

import static com.knot.backend.global.config.OpenApiConfig.ACCESS_TOKEN_COOKIE;

import com.knot.backend.auth.domain.model.AuthenticatedMember;
import com.knot.backend.global.response.ErrorResponse;
import com.knot.backend.recording.presentation.dto.response.RecentRecordingListResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;

@Tag(name = "녹음", description = "본인 녹음 세션")
@SecurityRequirement(name = ACCESS_TOKEN_COOKIE)
public interface RecentRecordingApi {

    // @formatter:off
    @Operation(
            summary = "최근 녹음 목록 조회",
            description = "현재 Workspace 멤버가 녹음을 시작한 Member와 관계없이 Workspace의 끝난 녹음을 조회한다. "
                    + "ENDED이고 음성 저장이 끝난 녹음은 문서 정리 중·전사 실패·NO_CONTENT·문서 생성 실패·완료와 관계없이 담고, "
                    + "음성 최종화를 기다리거나 진행 중인 녹음도 담는다. 녹음 중·일시정지·폐기·음성 저장 실패 녹음은 담지 않는다. "
                    + "시작 시각 내림차순, 동률이면 녹음 ID 내림차순이며 nextCursor로 이어 받는다. "
                    + "title은 서버가 만든다. 공개할 AI 녹음 제목이 없으면(정리 중·전사 실패·NO_CONTENT·제목 생성 실패) "
                    + "`{녹음을 시작한 Member 닉네임} 님의 녹음`이다. "
                    + "행마다 녹음 문서 조회와 같은 규칙의 processingStatus·retryable·retryableJobIds를 담고, 녹음한 Member·참여자 수는 담지 않는다. "
                    + "조회는 녹음·전사·제목·요약·문서 확인 상태를 바꾸지 않는다"
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "최근 녹음 한 페이지. 없으면 items는 빈 배열, nextCursor는 null",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = RecentRecordingListResponse.class))),
            @ApiResponse(responseCode = "400", description = "INVALID_PARAMETER: 경로 형식·size 범위 밖·빈 문자열이거나 해석할 수 없는 cursor. INVALID_WORKSPACE_ID: 양수가 아닌 Workspace ID",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "UNAUTHENTICATED: 로그인하지 않음",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "WORKSPACE_ACCESS_DENIED: 현재 Workspace 멤버가 아님",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "WORKSPACE_NOT_FOUND: Workspace가 없거나 삭제됨",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ErrorResponse.class)))
    })
    RecentRecordingListResponse findRecentRecordings(
            @Parameter(description = "현재 Workspace ID") long workspaceId,
            @Parameter(description = "이전 응답의 nextCursor. 첫 페이지는 생략하고 빈 문자열은 400") String cursor,
            @Parameter(description = "생략하면 20, 허용 범위 1~100",
                    schema = @Schema(type = "integer", minimum = "1", maximum = "100", defaultValue = "20")) Integer size,
            @Parameter(hidden = true) AuthenticatedMember authenticatedMember
    );
    // @formatter:on
}
