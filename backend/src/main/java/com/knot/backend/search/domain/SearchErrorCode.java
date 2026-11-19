package com.knot.backend.search.domain;

import com.knot.backend.global.exception.ErrorCategory;
import com.knot.backend.global.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum SearchErrorCode implements ErrorCode {
    INVALID_SEARCH_STATE(
            ErrorCategory.INTERNAL_SERVER_ERROR,
            "INVALID_SEARCH_STATE",
            "탐색 데이터 또는 상태가 올바르지 않습니다"
    ),
    INVALID_SEARCH_CONFIGURATION(
            ErrorCategory.INTERNAL_SERVER_ERROR,
            "INVALID_SEARCH_CONFIGURATION",
            "탐색 실행 설정이 올바르지 않습니다"
    ),
    VALIDATION_ERROR(
            ErrorCategory.INVALID_INPUT,
            "VALIDATION_ERROR",
            "질문 또는 requestId가 올바르지 않습니다"
    ),
    REQUEST_ID_CONFLICT(
            ErrorCategory.CONFLICT,
            "REQUEST_ID_CONFLICT",
            "다른 질문에 사용한 requestId입니다"
    ),
    ANSWER_ALREADY_IN_PROGRESS(
            ErrorCategory.CONFLICT,
            "ANSWER_ALREADY_IN_PROGRESS",
            "같은 대화의 답변이 아직 생성 중입니다"
    ),
    ANSWER_GENERATION_UNAVAILABLE(
            ErrorCategory.INTERNAL_SERVER_ERROR,
            "ANSWER_GENERATION_UNAVAILABLE",
            "답변 생성이 준비되지 않았습니다"
    ),
    INVALID_PARAMETER(
            ErrorCategory.INVALID_INPUT,
            "INVALID_PARAMETER",
            "요청 값이 올바르지 않습니다"
    ),
    SEARCH_ACCESS_DENIED(
            ErrorCategory.FORBIDDEN,
            "SEARCH_ACCESS_DENIED",
            "탐색 대화에 접근할 수 없습니다"
    ),
    ANSWER_NOT_FOUND(
            ErrorCategory.NOT_FOUND,
            "ANSWER_NOT_FOUND",
            "탐색 답변을 찾을 수 없습니다"
    ),
    QUESTION_NOT_FOUND(
            ErrorCategory.NOT_FOUND,
            "QUESTION_NOT_FOUND",
            "탐색 질문을 찾을 수 없습니다"
    ),
    ANSWER_NOT_FAILED(
            ErrorCategory.CONFLICT,
            "ANSWER_NOT_FAILED",
            "답변이 실패 상태가 아닙니다"
    ),
    ANSWER_NOT_RUNNING(
            ErrorCategory.CONFLICT,
            "ANSWER_NOT_RUNNING",
            "답변이 생성 중이 아닙니다"
    ),
    CONVERSATION_NOT_FOUND(
            ErrorCategory.NOT_FOUND,
            "CONVERSATION_NOT_FOUND",
            "탐색 대화를 찾을 수 없습니다"
    ),
    NOTION_CONNECTION_ALREADY_CONNECTED(
            ErrorCategory.CONFLICT,
            "NOTION_CONNECTION_ALREADY_CONNECTED",
            "이미 노션이 연결돼 있습니다"
    ),
    NOTION_CONNECTION_DISCONNECT_DENIED(
            ErrorCategory.FORBIDDEN,
            "NOTION_CONNECTION_DISCONNECT_DENIED",
            "노션 연결을 끊을 수 없습니다"
    ),
    NOTION_CONNECTION_NOT_FOUND(
            ErrorCategory.NOT_FOUND,
            "NOTION_CONNECTION_NOT_FOUND",
            "끊을 노션 연결이 없습니다"
    ),
    WRITE_CARD_NOT_FOUND(
            ErrorCategory.NOT_FOUND,
            "WRITE_CARD_NOT_FOUND",
            "쓰기 확인 카드를 찾을 수 없습니다"
    ),
    WRITE_CARD_NOT_EXECUTABLE(
            ErrorCategory.CONFLICT,
            "WRITE_CARD_NOT_EXECUTABLE",
            "실행할 수 없는 쓰기 확인 카드입니다"
    ),
    WRITE_CARD_NOT_PENDING(
            ErrorCategory.CONFLICT,
            "WRITE_CARD_NOT_PENDING",
            "대기 중인 쓰기 확인 카드가 아닙니다"
    );

    private final ErrorCategory category;
    private final String code;
    private final String message;
}
