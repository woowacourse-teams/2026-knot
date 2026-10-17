package com.knot.backend.global.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.knot.backend.global.exception.ErrorCode;
import java.util.List;
import java.util.Objects;

@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record ErrorResponse(
        String code,
        String message,
        List<FieldErrorResponse> fieldErrors,
        Integer expectedNextSequence
) {

    public ErrorResponse(
            String code,
            String message,
            List<FieldErrorResponse> fieldErrors
    ) {
        this(
                code,
                message,
                fieldErrors,
                null
        );
    }

    public ErrorResponse(ErrorCode errorCode) {
        this(
                errorCode,
                List.of()
        );
    }

    public ErrorResponse(
            ErrorCode errorCode,
            List<FieldErrorResponse> fieldErrors
    ) {
        this(
                requireErrorCode(errorCode).getCode(),
                requireErrorCode(errorCode).getMessage(),
                fieldErrors
        );
    }

    private static ErrorCode requireErrorCode(ErrorCode errorCode) {
        return Objects.requireNonNull(
                errorCode,
                "errorCode"
        );
    }
}
