package com.knot.backend.workspace.domain;

import com.knot.backend.global.exception.ErrorCategory;
import com.knot.backend.global.exception.ErrorCode;
import lombok.Getter;

@Getter
public enum WorkspaceErrorCode implements ErrorCode {
    INVALID_LIMIT(
            ErrorCategory.INVALID_INPUT,
            "INVALID_LIMIT",
            "조회 개수는 1~100이어야 합니다"
    ),
    INVALID_PARAMETER(
            ErrorCategory.INVALID_INPUT,
            "INVALID_PARAMETER",
            "커서 형식이 올바르지 않습니다"
    ),
    INVALID_WORKSPACE_NAME(
            ErrorCategory.INVALID_INPUT,
            "INVALID_WORKSPACE_NAME",
            "워크스페이스 이름이 올바르지 않습니다"
    ),

    INVALID_WORKSPACE_CREATED_AT(
            ErrorCategory.INVALID_INPUT,
            "INVALID_WORKSPACE_CREATED_AT",
            "워크스페이스 생성 시각이 올바르지 않습니다"
    ),

    INVALID_WORKSPACE_DELETED_AT(
            ErrorCategory.INVALID_INPUT,
            "INVALID_WORKSPACE_DELETED_AT",
            "워크스페이스 삭제 시각이 올바르지 않습니다"
    ),

    INVALID_WORKSPACE_ID(
            ErrorCategory.INVALID_INPUT,
            "INVALID_WORKSPACE_ID",
            "워크스페이스 ID가 올바르지 않습니다"
    ),

    INVALID_MEMBER_ID(
            ErrorCategory.INVALID_INPUT,
            "INVALID_MEMBER_ID",
            "멤버 ID가 올바르지 않습니다"
    ),

    INVALID_WORKSPACE_MEMBER_ROLE(
            ErrorCategory.INVALID_INPUT,
            "INVALID_WORKSPACE_MEMBER_ROLE",
            "워크스페이스 멤버 역할이 올바르지 않습니다"
    ),

    INVALID_WORKSPACE_MEMBER_JOINED_AT(
            ErrorCategory.INVALID_INPUT,
            "INVALID_WORKSPACE_MEMBER_JOINED_AT",
            "워크스페이스 참여 시각이 올바르지 않습니다"
    ),

    INVALID_WORKSPACE_MEMBER_LEFT_AT(
            ErrorCategory.INVALID_INPUT,
            "INVALID_WORKSPACE_MEMBER_LEFT_AT",
            "워크스페이스 탈퇴 시각이 올바르지 않습니다"
    ),

    INVALID_WORKSPACE_MEMBER_ACTIVE_COUNT(
            ErrorCategory.INVALID_INPUT,
            "INVALID_WORKSPACE_MEMBER_ACTIVE_COUNT",
            "워크스페이스 활성 멤버 수가 올바르지 않습니다"
    ),

    INVALID_WORKSPACE_INVITATION_LINK_TOKEN_HASH(
            ErrorCategory.INVALID_INPUT,
            "INVALID_WORKSPACE_INVITATION_LINK_TOKEN_HASH",
            "워크스페이스 초대 링크 토큰 해시가 올바르지 않습니다"
    ),

    INVALID_WORKSPACE_INVITATION_CODE_HASH(
            ErrorCategory.INVALID_INPUT,
            "INVALID_WORKSPACE_INVITATION_CODE_HASH",
            "워크스페이스 초대 코드 해시가 올바르지 않습니다"
    ),

    INVALID_WORKSPACE_INVITATION_CREATED_AT(
            ErrorCategory.INVALID_INPUT,
            "INVALID_WORKSPACE_INVITATION_CREATED_AT",
            "워크스페이스 초대 생성 시각이 올바르지 않습니다"
    ),

    INVALID_WORKSPACE_INVITATION_POINT_IN_TIME(
            ErrorCategory.INVALID_INPUT,
            "INVALID_WORKSPACE_INVITATION_POINT_IN_TIME",
            "워크스페이스 초대 확인 시각이 올바르지 않습니다"
    ),

    WORKSPACE_NOT_FOUND(
            ErrorCategory.NOT_FOUND,
            "WORKSPACE_NOT_FOUND",
            "워크스페이스를 찾을 수 없습니다"
    ),

    WORKSPACE_ACCESS_DENIED(
            ErrorCategory.FORBIDDEN,
            "WORKSPACE_ACCESS_DENIED",
            "워크스페이스에 접근할 수 없습니다"
    ),

    WORKSPACE_OWNER_REQUIRED(
            ErrorCategory.FORBIDDEN,
            "WORKSPACE_OWNER_REQUIRED",
            "워크스페이스 OWNER 권한이 필요합니다"
    ),

    WORKSPACE_OWNER_TRANSFER_REQUIRED(
            ErrorCategory.CONFLICT,
            "WORKSPACE_OWNER_TRANSFER_REQUIRED",
            "OWNER 권한을 승계하거나 워크스페이스를 삭제해야 합니다"
    ),

    WORKSPACE_MEMBER_ALREADY_LEFT(
            ErrorCategory.CONFLICT,
            "WORKSPACE_MEMBER_ALREADY_LEFT",
            "이미 탈퇴한 워크스페이스 멤버십입니다"
    ),

    INVALID_WORKSPACE_OWNERSHIP_TRANSFER_TARGET(
            ErrorCategory.INVALID_INPUT,
            "INVALID_WORKSPACE_OWNERSHIP_TRANSFER_TARGET",
            "본인에게 OWNER 권한을 승계할 수 없습니다"
    ),

    WORKSPACE_OWNERSHIP_TRANSFER_TARGET_CONFLICT(
            ErrorCategory.CONFLICT,
            "WORKSPACE_OWNERSHIP_TRANSFER_TARGET_CONFLICT",
            "OWNER 권한을 승계할 활성 멤버를 찾을 수 없습니다"
    ),

    WORKSPACE_INVITATION_PREVIEW_NOT_FOUND(
            ErrorCategory.NOT_FOUND,
            "WORKSPACE_INVITATION_PREVIEW_NOT_FOUND",
            "워크스페이스 초대를 찾을 수 없습니다"
    ),

    WORKSPACE_INVITATION_PREVIEW_RATE_LIMIT_EXCEEDED(
            ErrorCategory.TOO_MANY_REQUESTS,
            "WORKSPACE_INVITATION_PREVIEW_RATE_LIMIT_EXCEEDED",
            "워크스페이스 초대 코드 조회 요청이 너무 많습니다"
    ),

    WORKSPACE_INVITATION_SECRET_RECOVERY_FAILED(
            ErrorCategory.INTERNAL_SERVER_ERROR,
            "WORKSPACE_INVITATION_SECRET_RECOVERY_FAILED",
            "워크스페이스 초대를 복구할 수 없습니다"
    ),

    WORKSPACE_INVITATION_SECURITY_CONFIGURATION_INVALID(
            ErrorCategory.INTERNAL_SERVER_ERROR,
            "WORKSPACE_INVITATION_SECURITY_CONFIGURATION_INVALID",
            "워크스페이스 초대 보안 설정이 올바르지 않습니다"
    );

    private final ErrorCategory category;
    private final String code;
    private final String message;

    WorkspaceErrorCode(
            ErrorCategory category,
            String code,
            String message
    ) {
        this.category = category;
        this.code = code;
        this.message = message;
    }
}
