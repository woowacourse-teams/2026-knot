package com.knot.backend.recording.presentation;

import static com.knot.backend.global.config.OpenApiConfig.ACCESS_TOKEN_COOKIE;

import com.knot.backend.auth.domain.model.AuthenticatedMember;
import com.knot.backend.global.config.OpenApiConfig;
import com.knot.backend.global.response.ErrorResponse;
import com.knot.backend.recording.presentation.dto.request.RecordingReconnectRequest;
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
public interface RecordingReconnectApi {

    // @formatter:off
    @Operation(
            summary = "같은 탭 새로고침 뒤 녹음 이어 받기",
            description = "새로고침된 녹음 탭이 이전 tabId·controlToken과 새 tabId·controlToken을 함께 보내 같은 녹음을 이어 받는다. "
                    + "이전 증명이 현재 녹음 탭 증명이면 새 증명으로 바꾸고 마지막 생존 시각을 접수 시각으로 바꾼다. "
                    + "새 증명이 이미 현재 증명이면 같은 이어 받기의 재시도로 보고 같은 결과를 준다. 둘 다 아니면 403이다. "
                    + "동시에 보낸 두 탭 중 먼저 접수된 하나만 이어 받고, 바꾼 뒤 이전 증명의 요청은 403이다. "
                    + "녹음 상태와 녹음 시간은 바꾸지 않는다. 끊김 유예나 2시간 상한이 지났으면 먼저 끝내고, 끝난 녹음은 증명을 바꾸지 않고 "
                    + "200 ENDED로 끝난 결과를 준다. 구간 업로드 녹음은 확인되지 않은 예약 조각을 지우고 다음 예약 위치를 확인된 조각 "
                    + "바로 뒤로 되돌리므로 audio-save의 verifiedThroughSequence + 1, verifiedThroughFrame부터 이어 예약한다. "
                    + "응답은 생존 신호 응답과 같다",
            parameters = @Parameter(
                    name = OpenApiConfig.CSRF_TOKEN_HEADER_NAME,
                    in = ParameterIn.HEADER,
                    required = true,
                    schema = @Schema(type = "string"),
                    description = "CSRF 토큰"
            )
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "이어 받은 녹음(RECORDING·PAUSED) 또는 이미 끝난 녹음(ENDED)의 상태",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = RecordingHeartbeatResponse.class))),
            @ApiResponse(responseCode = "400",
                    description = "요청 본문 또는 식별자 형식 오류. INVALID_RECORDING_DATA: 새 증명이 이전 증명과 같음",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "인증되지 않은 요청",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403",
                    description = "워크스페이스 소속 없음, 녹음 시작자가 아님, 증명이 현재 녹음 탭 증명과 맞지 않음 또는 CSRF 검증 실패",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "워크스페이스 또는 해당 워크스페이스의 녹음 없음",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "409", description = "RECORDING_ALREADY_DISCARDED: 폐기된 녹음",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ErrorResponse.class)))
    })
    RecordingHeartbeatResponse reconnect(
            long workspaceId,
            @Parameter(description = "녹음 세션 ID", schema = @Schema(minimum = "1")) long recordingId,
            @Valid RecordingReconnectRequest request,
            @Parameter(hidden = true) AuthenticatedMember authenticatedMember
    );
    // @formatter:on
}
