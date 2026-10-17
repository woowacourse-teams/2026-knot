package com.knot.backend.recording.presentation;

import static com.knot.backend.global.config.OpenApiConfig.ACCESS_TOKEN_COOKIE;

import com.knot.backend.auth.domain.model.AuthenticatedMember;
import com.knot.backend.global.config.OpenApiConfig;
import com.knot.backend.global.response.ErrorResponse;
import com.knot.backend.recording.presentation.dto.request.RecordingEndRequest;
import com.knot.backend.recording.presentation.dto.response.RecordingEndResponse;
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
public interface RecordingEndApi {

    // @formatter:off
    @Operation(
            summary = "녹음 종료",
            description = "본문 없는 요청은 본인 녹음을 원격 종료한다. audioSeal 등록은 최초 탭의 tabId와 controlToken 증명이 필요하다. "
                    + "반복 종료도 증명을 먼저 확인한 뒤 처음 확정한 종료 결과를 반환한다. 종료는 업로드 완료나 STT 접수를 뜻하지 않는다",
            parameters = @Parameter(
                    name = OpenApiConfig.CSRF_TOKEN_HEADER_NAME,
                    in = ParameterIn.HEADER,
                    required = true,
                    schema = @Schema(type = "string"),
                    description = "CSRF 토큰"
            )
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "녹음 종료 또는 이미 종료된 결과",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = RecordingEndResponse.class))),
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
                            schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "409", description = "탈퇴·삭제로 폐기된 녹음",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ErrorResponse.class)))
    })
    RecordingEndResponse end(
            long workspaceId,
            @Parameter(description = "녹음 세션 ID", schema = @Schema(minimum = "1")) long recordingId,
            @Valid RecordingEndRequest request,
            @Parameter(hidden = true) AuthenticatedMember authenticatedMember
    );
    // @formatter:on
}
