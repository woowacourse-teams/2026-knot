package com.knot.backend.recording.presentation;

import static com.knot.backend.global.config.OpenApiConfig.ACCESS_TOKEN_COOKIE;

import com.knot.backend.auth.domain.model.AuthenticatedMember;
import com.knot.backend.global.response.ErrorResponse;
import com.knot.backend.recording.presentation.dto.response.RecordingAudioPlaybackResponse;
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

@Tag(name = "녹음", description = "녹음과 녹음 문서")
@SecurityRequirement(name = ACCESS_TOKEN_COOKIE)
public interface RecordingAudioPlaybackApi {

    // @formatter:off
    @Operation(
            summary = "녹음 음성 재생 조회",
            description = "녹음 문서 화면이 제목·요약·전사와 따로 음성 상태와 재생 주소를 불러온다. 현재 Workspace 멤버는 녹음을 시작한 Member·녹음 참여 "
                    + "여부와 관계없이 듣는다. 열 수 있는 녹음은 녹음 문서 조회와 같다. 백엔드는 음성을 중계하지 않고 최종 음성 파일의 저장소 버전에 고정한 "
                    + "서명 URL만 준다. 저장소 버전 기록이 없는 옛 업로드는 서명하지 않고 NO_AUDIO다. 만료되면 같은 요청으로 새 URL을 받는다. "
                    + "조회는 녹음·전사·처리 상태·문서 확인 기록을 바꾸지 않고 재생 기록을 남기지 않는다. 응답은 no-store다"
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "음성 상태. 준비 중·음성 없음도 200이다",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = RecordingAudioPlaybackResponse.class))),
            @ApiResponse(responseCode = "400", description = "INVALID_PARAMETER: 경로 형식 오류. INVALID_WORKSPACE_ID: 양수가 아닌 Workspace ID. INVALID_RECORDING_DATA: 양수가 아닌 recordingId",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "UNAUTHENTICATED: 로그인 필요",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "WORKSPACE_ACCESS_DENIED: 현재 Workspace 멤버가 아님",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "WORKSPACE_NOT_FOUND: Workspace가 없거나 삭제됨. "
                    + "RECORDING_NOT_FOUND: 없는 녹음, 다른 Workspace의 녹음, 녹음 문서를 열 수 없는 녹음(녹음 중·일시정지·폐기·음성 저장 실패)",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "500", description = "AUDIO_STORAGE_UNAVAILABLE: 음성 불러오기 실패. 저장소 확인·서명 발급이 안 되거나 저장된 객체가 기록과 다름",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ErrorResponse.class)))
    })
    ResponseEntity<RecordingAudioPlaybackResponse> findPlayback(
            @Parameter(description = "현재 Workspace ID") long workspaceId,
            @Parameter(description = "녹음 세션 ID. 최근 녹음 목록의 recordingId") long recordingId,
            @Parameter(hidden = true) AuthenticatedMember authenticatedMember
    );
    // @formatter:on
}
