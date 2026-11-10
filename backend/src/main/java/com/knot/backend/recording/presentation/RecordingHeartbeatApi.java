package com.knot.backend.recording.presentation;

import static com.knot.backend.global.config.OpenApiConfig.ACCESS_TOKEN_COOKIE;

import com.knot.backend.auth.domain.model.AuthenticatedMember;
import com.knot.backend.global.config.OpenApiConfig;
import com.knot.backend.global.response.ErrorResponse;
import com.knot.backend.recording.presentation.dto.request.RecordingControlRequest;
import com.knot.backend.recording.presentation.dto.response.RecordingHeartbeatResponse;
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
import org.springframework.http.MediaType;

@Tag(name = "녹음", description = "본인 녹음 세션")
@SecurityRequirement(name = ACCESS_TOKEN_COOKIE)
public interface RecordingHeartbeatApi {

    // @formatter:off
    @Operation(
            summary = "녹음 생존 신호",
            description = "녹음 탭이 10초마다 보낸다. 현재 녹음 탭 증명(tabId·controlToken, 이어 받기로 바뀌었으면 바뀐 값)으로 증명한다. "
                    + "생존 신호는 신호·일시정지·이어서 녹음·조각 예약·조각 완료 확인·이어 받기다. "
                    + "서버는 신호를 반영하기 전에 마지막 생존 시각부터 끊김 유예(기본 30분)가 지났는지 먼저 판정하고, 지났으면 "
                    + "마지막 생존 시각을 끝난 시각으로 CONNECTION_EXPIRED 종료를 확정한다. 2시간 상한에 먼저 닿았으면 MAX_DURATION이다. "
                    + "늦은 신호는 종료를 되돌리지 않는다. 이미 끝난 녹음도 200으로 종료 상태를 돌려주며 lastSeenAt을 갱신하지 않는다. "
                    + "connectionStatus는 응답 시각으로 계산하며 진행 중 녹음의 신호 응답은 CONNECTED다",
            parameters = @Parameter(
                    name = OpenApiConfig.CSRF_TOKEN_HEADER_NAME,
                    in = ParameterIn.HEADER,
                    required = true,
                    schema = @Schema(type = "string"),
                    description = "CSRF 토큰"
            )
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "신호 반영 결과 또는 종료된 녹음의 상태",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = RecordingHeartbeatResponse.class))),
            @ApiResponse(responseCode = "400", description = "요청 본문 또는 식별자 형식 오류",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "인증되지 않은 요청",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403",
                    description = "워크스페이스 소속 없음, 녹음 시작자나 최초 탭이 아님 또는 CSRF 검증 실패",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "워크스페이스 또는 해당 워크스페이스의 녹음 없음",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ErrorResponse.class)))
    })
    RecordingHeartbeatResponse heartbeat(
            long workspaceId,
            @Parameter(description = "녹음 세션 ID", schema = @Schema(minimum = "1")) long recordingId,
            @Valid RecordingControlRequest request,
            @Parameter(hidden = true) AuthenticatedMember authenticatedMember
    );
    // @formatter:on
}
