package com.knot.backend.recording.presentation;

import static com.knot.backend.global.config.OpenApiConfig.ACCESS_TOKEN_COOKIE;

import com.knot.backend.auth.domain.model.AuthenticatedMember;
import com.knot.backend.global.response.ErrorResponse;
import com.knot.backend.recording.presentation.dto.response.RecordingDocumentResponse;
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
public interface RecordingDocumentApi {

    // @formatter:off
    @Operation(
            summary = "녹음 문서 조회",
            description = "녹음 문서 화면의 표시 제목·시작 시각·길이·처리 상태·다시 시도 가능 여부·회의 전체 요약을 준다. "
                    + "현재 Workspace 멤버는 녹음을 시작한 Member·녹음 참여 여부와 관계없이 연다. 열 수 있는 녹음은 최근 녹음 목록의 포함 조건과 같다. "
                    + "녹음 문서는 주제별 Document가 아니며 문서 목록·확인·탐색 대상이 아니다. 전사는 녹음 전사 조회로 따로 부른다. "
                    + "처리 실패는 200의 processingStatus로 나타낸다. 회의 전체 요약의 상태는 meetingSummary에만 나타나고 processingStatus·retryable·retryableJobIds에 영향을 주지 않는다. "
                    + "retryable과 meetingSummary.retryable은 재시도 횟수·실패 후 경과 기간·녹음 시작자 여부와 무관하게 현재 접수 조건으로만 계산하며 이후 접수 성공을 보장하지 않는다. "
                    + "조회는 녹음·전사·제목·요약 상태와 문서 확인 기록을 바꾸지 않고 요약을 새로 접수하지 않는다"
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "녹음 문서의 제목·처리 상태·다시 시도 가능 여부·회의 전체 요약",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = RecordingDocumentResponse.class))),
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
                            schema = @Schema(implementation = ErrorResponse.class)))
    })
    RecordingDocumentResponse findDocument(
            @Parameter(description = "현재 Workspace ID") long workspaceId,
            @Parameter(description = "녹음 세션 ID. 최근 녹음 목록의 recordingId") long recordingId,
            @Parameter(hidden = true) AuthenticatedMember authenticatedMember
    );
    // @formatter:on
}
