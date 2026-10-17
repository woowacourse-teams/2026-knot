package com.knot.backend.global.exception;

import com.knot.backend.global.response.ErrorResponse;
import com.knot.backend.global.logging.ServerErrorLogger;
import lombok.extern.slf4j.Slf4j;
import com.knot.backend.global.response.FieldErrorResponse;
import com.knot.backend.recording.domain.AudioSequenceGapException;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.ElementKind;
import jakarta.validation.Path;
import java.util.List;
import java.util.stream.StreamSupport;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    @ExceptionHandler(ProjectException.class)
    public ResponseEntity<ErrorResponse> handleProjectException(ProjectException exception) {
        ServerErrorLogger.record(
                log,
                "HTTP project failure code=" + exception.getErrorCode()
                        .getCode(),
                exception
        );
        ResponseEntity<ErrorResponse> response = respond(exception.getErrorCode());
        if (RetryAfterException.class.isInstance(exception)) {
            RetryAfterException retryAfterException = RetryAfterException.class.cast(exception);
            return ResponseEntity.status(response.getStatusCode())
                    .headers(response.getHeaders())
                    .header(
                            HttpHeaders.RETRY_AFTER,
                            String.valueOf(retryAfterException.getRetryAfterSeconds())
                    )
                    .body(response.getBody());
        }
        return response;
    }

    @ExceptionHandler(AudioSequenceGapException.class)
    public ResponseEntity<ErrorResponse> handleAudioSequenceGap(AudioSequenceGapException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(
                        new ErrorResponse(
                                exception.getErrorCode()
                                        .getCode(),
                                exception.getErrorCode()
                                        .getMessage(),
                                List.of(),
                                exception.getExpectedNextSequence()
                        )
                );
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidationException(MethodArgumentNotValidException exception) {
        List<FieldErrorResponse> fieldErrors = exception.getBindingResult()
                .getFieldErrors()
                .stream()
                .map(
                        error -> new FieldErrorResponse(
                                error.getField(),
                                error.getDefaultMessage()
                        )
                )
                .toList();

        return respond(
                CommonErrorCode.VALIDATION_ERROR,
                fieldErrors
        );
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ErrorResponse> handleConstraintViolationException(ConstraintViolationException exception) {
        List<FieldErrorResponse> fieldErrors = exception.getConstraintViolations()
                .stream()
                .map(this::toFieldErrorResponse)
                .toList();

        return respond(
                CommonErrorCode.VALIDATION_ERROR,
                fieldErrors
        );
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleUnreadableMessage(HttpMessageNotReadableException ignored) {
        return respond(CommonErrorCode.INVALID_REQUEST_BODY);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handleTypeMismatch(MethodArgumentTypeMismatchException exception) {
        FieldErrorResponse fieldError = new FieldErrorResponse(
                exception.getName(),
                CommonErrorCode.INVALID_PARAMETER.getMessage()
        );

        return respond(
                CommonErrorCode.INVALID_PARAMETER,
                List.of(fieldError)
        );
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ErrorResponse> handleMissingParameter(MissingServletRequestParameterException exception) {

        FieldErrorResponse fieldError = new FieldErrorResponse(
                exception.getParameterName(),
                CommonErrorCode.MISSING_PARAMETER.getMessage()
        );

        return respond(
                CommonErrorCode.MISSING_PARAMETER,
                List.of(fieldError)
        );
    }

    @ExceptionHandler({NoResourceFoundException.class, NoHandlerFoundException.class})
    public ResponseEntity<ErrorResponse> handlePathNotFound(Exception ignored) {
        return respond(
                CommonErrorCode.REQUEST_PATH_NOT_FOUND,
                HttpHeaders.EMPTY
        );
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ErrorResponse> handleMethodNotSupported(HttpRequestMethodNotSupportedException exception) {
        return respond(
                CommonErrorCode.METHOD_NOT_ALLOWED,
                exception.getHeaders()
        );
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ErrorResponse> handleMediaTypeNotSupported(HttpMediaTypeNotSupportedException exception) {
        return respond(
                CommonErrorCode.UNSUPPORTED_MEDIA_TYPE,
                exception.getHeaders()
        );
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpectedException(Exception exception) {
        ServerErrorLogger.record(
                log,
                "HTTP unexpected failure",
                exception
        );
        return respond(CommonErrorCode.INTERNAL_SERVER_ERROR);
    }

    private FieldErrorResponse toFieldErrorResponse(ConstraintViolation<?> violation) {
        return new FieldErrorResponse(
                extractFieldName(violation.getPropertyPath()),
                violation.getMessage()
        );
    }

    private String extractFieldName(Path path) {
        return StreamSupport.stream(
                path.spliterator(),
                false
        )
                .filter(this::isFieldNode)
                .map(Path.Node::getName)
                .filter(this::isUsableFieldName)
                .reduce(
                        (
                                first,
                                second
                        ) -> second
                )
                .orElse("request");
    }

    private boolean isFieldNode(Path.Node node) {
        return node.getKind() == ElementKind.PROPERTY || node.getKind() == ElementKind.PARAMETER;
    }

    private boolean isUsableFieldName(String fieldName) {
        return fieldName != null && !fieldName.matches("arg\\d+");
    }

    private ResponseEntity<ErrorResponse> respond(ErrorCode errorCode) {
        return respond(
                errorCode,
                List.of()
        );
    }

    private ResponseEntity<ErrorResponse> respond(
            ErrorCode errorCode,
            List<FieldErrorResponse> fieldErrors
    ) {
        return ResponseEntity.status(toHttpStatus(errorCode.getCategory()))
                .body(
                        new ErrorResponse(
                                errorCode,
                                fieldErrors
                        )
                );
    }

    private ResponseEntity<ErrorResponse> respond(
            ErrorCode errorCode,
            HttpHeaders headers
    ) {
        return ResponseEntity.status(toHttpStatus(errorCode.getCategory()))
                .headers(headers)
                .contentType(MediaType.APPLICATION_JSON)
                .body(new ErrorResponse(errorCode));
    }

    private HttpStatus toHttpStatus(ErrorCategory category) {
        return switch (category) {
            case INVALID_INPUT -> HttpStatus.BAD_REQUEST;
            case UNAUTHORIZED -> HttpStatus.UNAUTHORIZED;
            case FORBIDDEN -> HttpStatus.FORBIDDEN;
            case NOT_FOUND -> HttpStatus.NOT_FOUND;
            case METHOD_NOT_ALLOWED -> HttpStatus.METHOD_NOT_ALLOWED;
            case UNSUPPORTED_MEDIA_TYPE -> HttpStatus.UNSUPPORTED_MEDIA_TYPE;
            case CONFLICT -> HttpStatus.CONFLICT;
            case TOO_MANY_REQUESTS -> HttpStatus.TOO_MANY_REQUESTS;
            case INTERNAL_SERVER_ERROR -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
    }
}
