package com.knot.backend.recording.presentation;

import static com.knot.backend.global.config.OpenApiConfig.ACCESS_TOKEN_COOKIE;

import com.knot.backend.auth.domain.model.AuthenticatedMember;
import com.knot.backend.global.response.ErrorResponse;
import com.knot.backend.recording.presentation.dto.response.RecordingCurrentResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

@Tag(name = "녹음", description = "본인 녹음 세션")
@SecurityRequirement(name = ACCESS_TOKEN_COOKIE)
public interface RecordingCurrentApi {

    // @formatter:off
    @Operation(
            summary = "내 현재 녹음 조회",
            description = "요청한 Workspace에서 본인의 녹음을 RECORDING·PAUSED, PROCESSING, 실패 순으로 선택한다. "
                    + "같은 우선순위에서는 시작 시각, ID 내림차순으로 선택하며 새 녹음 완료 후 이전 실패를 복원한다. "
                    + "전사 실패는 경과 일수와 관계없이 표시하며 실제 사용할 오디오가 없으면 retryable=false다. "
                    + "문서 실패는 마지막 실패 후 168시간 이내에 표시하고 기존 재시도 조건을 만족하는 retryableJobIds만 반환한다. "
                    + "다른 탭·기기에서도 조회할 수 있지만 생존 신호를 갱신하거나 제어 권한을 넘기지 않고 제어 비밀값을 반환하지 않는다. "
                    + "다른 Workspace의 녹음과 완료·내용 없음·폐기는 제외한다"
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "본인 녹음의 진행 또는 실패 상태와 재시도 가능 여부",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = RecordingCurrentResponse.class))),
            @ApiResponse(responseCode = "204", description = "표시할 본인 녹음이 없음", content = @Content),
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
    ResponseEntity<RecordingCurrentResponse> findCurrent(
            long workspaceId,
            @Parameter(hidden = true) AuthenticatedMember authenticatedMember
    );
    // @formatter:on
}
