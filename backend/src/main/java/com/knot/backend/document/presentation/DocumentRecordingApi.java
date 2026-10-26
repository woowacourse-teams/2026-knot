package com.knot.backend.document.presentation;

import static com.knot.backend.global.config.OpenApiConfig.ACCESS_TOKEN_COOKIE;

import com.knot.backend.auth.domain.model.AuthenticatedMember;
import com.knot.backend.document.presentation.dto.response.DocumentRecordingListResponse;
import com.knot.backend.global.response.ErrorResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;

@Tag(name = "문서", description = "생성된 읽기 전용 문서와 확인 현황 조회")
@SecurityRequirement(name = ACCESS_TOKEN_COOKIE)
public interface DocumentRecordingApi {

    // @formatter:off
    @Operation(
            summary = "녹음별 문서 목록 조회",
            description = "문서 탭 「녹음별」 탭용이다. 현재 Workspace의 Document가 하나 이상 연결된 녹음만 녹음을 시작한 Member와 관계없이 묶는다. "
                    + "Document는 주제별 문서 목록과 같은 집합(DRAFT·ARCHIVED, 주제 배정·내 확인 상태 무관)이며 "
                    + "문서가 없는 녹음·문서 생성 Job·녹음 문서는 담지 않는다. "
                    + "녹음은 시작 시각 내림차순, 동률이면 녹음 ID 내림차순으로 한 페이지에 최대 20개이고 nextCursor로 이어 받는다. "
                    + "녹음 하나의 Document는 나누지 않고 생성 시각 오름차순, 동률이면 ID 오름차순으로 모두 담는다. "
                    + "title은 최근 녹음 목록과 같은 표시 제목이다. 조회는 문서 확인·문서·녹음·전사·제목·요약 상태를 바꾸지 않는다"
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "녹음 묶음 한 페이지. 없으면 items는 빈 배열, nextCursor는 null",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = DocumentRecordingListResponse.class))),
            @ApiResponse(responseCode = "400", description = "INVALID_PARAMETER: 경로 형식 오류, 빈 문자열이거나 해석할 수 없는 cursor, 다른 Workspace에서 받은 cursor",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "UNAUTHENTICATED: 로그인하지 않음",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "WORKSPACE_ACCESS_DENIED: 현재 Workspace 멤버가 아님",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ErrorResponse.class)))
    })
    DocumentRecordingListResponse findDocumentRecordings(
            @Parameter(description = "현재 Workspace ID") Long workspaceId,
            @Parameter(description = "이전 응답의 nextCursor. 첫 페이지는 생략하고 빈 문자열은 400") String cursor,
            @Parameter(hidden = true) AuthenticatedMember authenticatedMember
    );
    // @formatter:on
}
