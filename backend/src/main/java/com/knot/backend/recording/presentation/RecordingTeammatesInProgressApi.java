package com.knot.backend.recording.presentation;

import static com.knot.backend.global.config.OpenApiConfig.ACCESS_TOKEN_COOKIE;

import com.knot.backend.auth.domain.model.AuthenticatedMember;
import com.knot.backend.global.response.ErrorResponse;
import com.knot.backend.recording.presentation.dto.response.TeammateRecordingListResponse;
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
public interface RecordingTeammatesInProgressApi {

    // @formatter:off
    @Operation(
            summary = "팀원 진행 중 녹음 조회",
            description = "요청한 Workspace에서 본인을 뺀 Member의 RECORDING·PAUSED 녹음을 시작 시각, ID 내림차순으로 모두 반환한다. "
                    + "본인 녹음은 내 현재 녹음 조회가 맡는다. 조회는 생존 신호나 누적 시간을 바꾸지 않고 제어 비밀값, 연결 상태, Member ID를 반환하지 않는다. "
                    + "ENDED·DISCARDED와 다른 Workspace의 녹음은 제외하며, 없으면 빈 배열이다"
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "팀원의 진행 중 녹음 목록",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = TeammateRecordingListResponse.class))),
            @ApiResponse(responseCode = "400", description = "워크스페이스 ID 형식 오류",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "인증되지 않은 요청",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "워크스페이스 소속 없음",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "워크스페이스 없음",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ErrorResponse.class)))
    })
    TeammateRecordingListResponse findTeammatesInProgress(
            long workspaceId,
            @Parameter(hidden = true) AuthenticatedMember authenticatedMember
    );
    // @formatter:on
}
