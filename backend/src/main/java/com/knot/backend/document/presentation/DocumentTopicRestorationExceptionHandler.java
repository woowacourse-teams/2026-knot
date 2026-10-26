package com.knot.backend.document.presentation;

import com.knot.backend.document.domain.DocumentException;
import com.knot.backend.global.exception.ErrorCategory;
import com.knot.backend.global.exception.GlobalExceptionHandler;
import com.knot.backend.global.response.ErrorResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = DocumentTopicRestorationController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
@RequiredArgsConstructor
public class DocumentTopicRestorationExceptionHandler {

    private final GlobalExceptionHandler global;

    @ExceptionHandler(DocumentException.class)
    public ResponseEntity<ErrorResponse> handleDocumentException(DocumentException exception) {
        if (exception.getErrorCode()
                .getCategory() == ErrorCategory.INTERNAL_SERVER_ERROR) {
            return global.handleUnexpectedException(exception);
        }
        return global.handleProjectException(exception);
    }
}
