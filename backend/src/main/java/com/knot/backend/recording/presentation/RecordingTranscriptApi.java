package com.knot.backend.recording.presentation;

import static com.knot.backend.global.config.OpenApiConfig.ACCESS_TOKEN_COOKIE;

import com.knot.backend.auth.domain.model.AuthenticatedMember;
import com.knot.backend.global.response.ErrorResponse;
import com.knot.backend.recording.presentation.dto.response.RecordingTranscriptResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;

@Tag(name = "녹음", description = "녹음과 녹음 문서")
@SecurityRequirement(name = ACCESS_TOKEN_COOKIE)
public interface RecordingTranscriptApi {

    // @formatter:off
    @Operation(
            summary = "녹음 전사 조회",
            description = "녹음 문서 화면이 제목·요약과 따로 전사를 불러온다. 현재 Workspace 멤버는 녹음을 시작한 Member·녹음 참여 여부와 "
                    + "관계없이 조회한다. 열 수 있는 녹음은 최근 녹음 목록의 포함 조건과 같다. "
                    + "성공 Transcript가 저장되고 NO_CONTENT가 아님이 정해진 녹음에서만 200이며, 그 녹음의 문서 정리 입력으로 쓴 "
                    + "전체 원문과 구간을 문서 원문 조회와 같은 데이터·정렬로 준다. 화면은 processingStatus가 DOCUMENT_FAILED·COMPLETED일 때 부른다. "
                    + "조회는 녹음·전사·문서 확인 기록을 바꾸지 않는다"
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "문서 정리 입력으로 쓴 성공 Transcript 전체 원문과 구간",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = RecordingTranscriptResponse.class))),
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
                    + "RECORDING_NOT_FOUND: 없는 녹음, 다른 Workspace의 녹음, 녹음 문서를 열 수 없는 녹음(녹음 중·일시정지·폐기·음성 저장 실패). "
                    + "RECORDING_TRANSCRIPT_NOT_FOUND: 보여 줄 전사가 없음(전사 전·전사 실패·NO_CONTENT)",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ErrorResponse.class)))
    })
    RecordingTranscriptResponse findTranscript(
            @Parameter(description = "현재 Workspace ID") long workspaceId,
            @Parameter(description = "녹음 세션 ID. 최근 녹음 목록의 recordingId") long recordingId,
            @Parameter(hidden = true) AuthenticatedMember authenticatedMember
    );
    // @formatter:on
}
