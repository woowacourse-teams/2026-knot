package com.knot.backend.search.presentation;

import static com.knot.backend.global.config.OpenApiConfig.ACCESS_TOKEN_COOKIE;

import com.knot.backend.auth.domain.model.AuthenticatedMember;
import com.knot.backend.global.config.OpenApiConfig;
import com.knot.backend.global.response.ErrorResponse;
import com.knot.backend.search.presentation.dto.response.SearchWriteCardResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;

@Tag(name = "탐색", description = "Search V2 개인 대화")
@SecurityRequirement(name = ACCESS_TOKEN_COOKIE)
public interface SearchWriteCardApi {

    // @formatter:off
    @Operation(
            summary = "쓰기 확인 카드 실행",
            description = "카드의 질문을 한 현재 Workspace 멤버만 실행합니다. 노션 연결의 연결한 사람 권한으로 페이지를 한 번만 만들고 실행이 끝난 카드를 돌려줍니다. "
                    + "다시 누를 수 있는 오류는 status=PENDING과 retryReason, 다시 실행할 수 없는 오류는 status=FAILED와 failureReason으로 알립니다. "
                    + "대기가 아닌 카드에 온 요청은 노션에 다시 쓰지 않고 지금 상태를 돌려주며, 실행 중인 카드는 끝날 때까지 잠시 기다립니다.",
            parameters = @Parameter(
                    name = OpenApiConfig.CSRF_TOKEN_HEADER_NAME,
                    in = ParameterIn.HEADER,
                    required = true,
                    schema = @Schema(type = "string"),
                    description = "CSRF 토큰"
            )
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "실행이 끝난 뒤의 카드. 노션 오류도 카드 상태로 알린다",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = SearchWriteCardResponse.class))),
            @ApiResponse(responseCode = "401", description = "UNAUTHENTICATED: 로그인하지 않음",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403",
                    description = "SEARCH_ACCESS_DENIED: 현재 멤버가 아니거나 본인 대화가 아니거나 카드의 질문을 한 멤버가 아님, 또는 CSRF 검증 실패",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "WRITE_CARD_NOT_FOUND: 대화에 카드가 없음",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "409", description = "WRITE_CARD_NOT_EXECUTABLE: DB 속성을 확인하지 못한 카드",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    SearchWriteCardResponse execute(
            @Parameter(description = "Workspace ID", schema = @Schema(minimum = "1")) Long workspaceId,
            @Parameter(description = "본인 대화 ID", schema = @Schema(minimum = "1")) Long conversationId,
            @Parameter(description = "쓰기 확인 카드 ID", schema = @Schema(minimum = "1")) Long cardId,
            @Parameter(hidden = true) AuthenticatedMember authenticatedMember
    );

    @Operation(
            summary = "쓰기 확인 카드 취소",
            description = "카드의 질문을 한 현재 Workspace 멤버만 대기인 카드를 취소합니다. 이미 취소된 카드는 지금 상태를 200으로 돌려줍니다.",
            parameters = @Parameter(
                    name = OpenApiConfig.CSRF_TOKEN_HEADER_NAME,
                    in = ParameterIn.HEADER,
                    required = true,
                    schema = @Schema(type = "string"),
                    description = "CSRF 토큰"
            )
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "취소된 카드",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = SearchWriteCardResponse.class))),
            @ApiResponse(responseCode = "401", description = "UNAUTHENTICATED: 로그인하지 않음",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403",
                    description = "SEARCH_ACCESS_DENIED: 현재 멤버가 아니거나 본인 대화가 아니거나 카드의 질문을 한 멤버가 아님, 또는 CSRF 검증 실패",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "WRITE_CARD_NOT_FOUND: 대화에 카드가 없음",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "409", description = "WRITE_CARD_NOT_PENDING: 실행 중, 실행됨, 실패인 카드",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    SearchWriteCardResponse cancel(
            @Parameter(description = "Workspace ID", schema = @Schema(minimum = "1")) Long workspaceId,
            @Parameter(description = "본인 대화 ID", schema = @Schema(minimum = "1")) Long conversationId,
            @Parameter(description = "쓰기 확인 카드 ID", schema = @Schema(minimum = "1")) Long cardId,
            @Parameter(hidden = true) AuthenticatedMember authenticatedMember
    );
    // @formatter:on
}
