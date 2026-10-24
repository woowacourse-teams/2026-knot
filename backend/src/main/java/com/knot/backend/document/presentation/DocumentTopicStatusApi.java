package com.knot.backend.document.presentation;

import static com.knot.backend.global.config.OpenApiConfig.ACCESS_TOKEN_COOKIE;

import com.knot.backend.auth.domain.model.AuthenticatedMember;
import com.knot.backend.document.presentation.dto.response.DocumentTopicStatusResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Positive;
import org.springframework.http.ResponseEntity;

@Tag(name = "문서 주제 정리")
@SecurityRequirement(name = ACCESS_TOKEN_COOKIE)
public interface DocumentTopicStatusApi {

    @Operation(summary = "주제 정리 상태 조회", description = """
            현재 OWNER가 작업 ID 없이 진행 중 작업 또는 최근 종료 작업을 조회합니다. GET은 CSRF를 요구하지 않습니다.
            진행 중에만 앞 요청 완료 후 3초 간격으로 조회합니다. 준비 수가 대상 수와 같아도 완료는 SUCCEEDED로 판단합니다.

            | operation.status | 뜻 | 진행 여부 | 취소 가능 |
            | --- | --- | --- | --- |
            | QUEUED | 최초 또는 다음 단계 대기 | 진행 중 | 가능 |
            | RUNNING | 처리 또는 자동 재시도 대기 | 진행 중 | 가능 |
            | APPLYING | 최종 결과 반영 | 진행 중 | 불가 |
            | CANCEL_REQUESTED | 취소 접수 후 종료 대기 | 진행 중 | 이미 접수됨 |
            | CANCELLED | 취소 완료 | 종료 | 불가 |
            | SUCCEEDED | 전체 적용 완료 | 종료 | 불가 |
            | FAILED | 최종 실패 | 종료 | 불가 |

            APPLYING과 CANCEL_REQUESTED도 공통 상태이며 실제 전환은 각 실행·취소 구현을 따릅니다.
            시작·조회·취소·복원은 공통 상태를 사용하되 취소 성공 응답은 CANCEL_REQUESTED/CANCELLED입니다.
            작업 실패도 정상 조회면 HTTP 200이고 failure는 FAILED일 때만 안전한 안내를 제공합니다.
            공개 작업·복원 버전 ID는 Long이며 requestId만 UUID입니다. 과거 startedAt 미기록은 null입니다.
            진행 중 복원 정보가 남아 있어도 canRestore=false입니다. 실패·취소는 기존 공개 분류를 보존합니다.
            """)
    @ApiResponses({@ApiResponse(responseCode = "200", description = "작업 없음과 실패 상태를 포함한 정상 조회"),
            @ApiResponse(responseCode = "400", description = "VALIDATION_ERROR: 양수가 아닌 경로, INVALID_PARAMETER: Long 변환 불가"),
            @ApiResponse(responseCode = "401", description = "UNAUTHENTICATED 또는 INVALID_JWT: 인증 오류"),
            @ApiResponse(responseCode = "403", description = "WORKSPACE_ACCESS_DENIED 또는 WORKSPACE_OWNER_REQUIRED"),
            @ApiResponse(responseCode = "404", description = "WORKSPACE_NOT_FOUND: 없거나 삭제된 Workspace")})
    ResponseEntity<DocumentTopicStatusResponse> find(
            @Positive long workspaceId,
            @Parameter(hidden = true) AuthenticatedMember member
    );
}
