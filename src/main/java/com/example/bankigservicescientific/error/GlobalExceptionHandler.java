package com.example.bankigservicescientific.error;

import java.util.stream.Collectors;

import jakarta.servlet.ServletException;
import com.example.bankigservicescientific.account.AccountNotFoundException;
import com.example.bankigservicescientific.transfer.IdempotencyConflictException;
import com.example.bankigservicescientific.transfer.InsufficientFundsException;
import com.example.bankigservicescientific.transfer.InvalidTransferException;
import com.example.bankigservicescientific.transfer.TransferNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Maps exceptions to the single error body {code, message, timestamp}.
 * Stack traces and SQL never reach the HTTP response; they are only logged.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(AccountNotFoundException.class)
    ResponseEntity<ApiError> accountNotFound(AccountNotFoundException e) {
        return respond(HttpStatus.NOT_FOUND, "ACCOUNT_NOT_FOUND", e.getMessage());
    }

    @ExceptionHandler(InvalidTransferException.class)
    ResponseEntity<ApiError> invalidTransfer(InvalidTransferException e) {
        return respond(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", e.getMessage());
    }

    @ExceptionHandler(InsufficientFundsException.class)
    ResponseEntity<ApiError> insufficientFunds(InsufficientFundsException e) {
        return respond(HttpStatus.CONFLICT, "INSUFFICIENT_FUNDS", e.getMessage());
    }

    @ExceptionHandler(TransferNotFoundException.class)
    ResponseEntity<ApiError> transferNotFound(TransferNotFoundException e) {
        return respond(HttpStatus.NOT_FOUND, "TRANSFER_NOT_FOUND", e.getMessage());
    }

    @ExceptionHandler(IdempotencyConflictException.class)
    ResponseEntity<ApiError> idempotencyConflict(IdempotencyConflictException e) {
        return respond(HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_CONFLICT", e.getMessage());
    }

    /** Backstop: the UNIQUE constraint on idempotency_key fired although the key lookup found nothing. */
    @ExceptionHandler(DuplicateKeyException.class)
    ResponseEntity<ApiError> duplicateKey(DuplicateKeyException e) {
        // Deliberately without the exception: its message contains the full idempotency key.
        log.warn("Unique constraint violated on transfers (idempotency key)");
        return respond(HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_CONFLICT", "Idempotency-Key was already used");
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiError> invalidBody(MethodArgumentNotValidException e) {
        String fields = e.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField())
                .distinct()
                .collect(Collectors.joining(", "));
        return respond(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "Invalid or missing fields: " + fields);
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    ResponseEntity<ApiError> missingHeader(MissingRequestHeaderException e) {
        return respond(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "Required header '" + e.getHeaderName() + "' is missing");
    }

    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<ApiError> noResource(NoResourceFoundException e) {
        return respond(HttpStatus.NOT_FOUND, "NOT_FOUND", "Resource not found");
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<ApiError> badArgument(MethodArgumentTypeMismatchException e) {
        return respond(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "Invalid value for parameter '" + e.getName() + "'");
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ApiError> unreadableBody(HttpMessageNotReadableException e) {
        return respond(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "Malformed request body");
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ResponseEntity<ApiError> methodNotAllowed(HttpRequestMethodNotSupportedException e) {
        return respond(HttpStatus.METHOD_NOT_ALLOWED, "METHOD_NOT_ALLOWED", "HTTP method not supported");
    }

    @ExceptionHandler({CannotGetJdbcConnectionException.class, DataAccessResourceFailureException.class,
            QueryTimeoutException.class})
    ResponseEntity<ApiError> databaseUnavailable(Exception e) {
        log.error("Database unavailable", e);
        return respond(HttpStatus.SERVICE_UNAVAILABLE, "SERVICE_UNAVAILABLE", "Service temporarily unavailable");
    }

    /** Other Spring MVC request errors (415, 406, missing parameter, ...) keep the status Spring chose for them. */
    @ExceptionHandler(ServletException.class)
    ResponseEntity<ApiError> servletError(ServletException e) {
        return e instanceof ErrorResponse errorResponse ? errorResponse(e, errorResponse) : unexpected(e);
    }

    /** Includes ResponseStatusException, which Actuator endpoints throw for bad parameters (400). */
    @ExceptionHandler(ErrorResponseException.class)
    ResponseEntity<ApiError> errorResponseException(ErrorResponseException e) {
        return errorResponse(e, e);
    }

    private ResponseEntity<ApiError> errorResponse(Exception e, ErrorResponse errorResponse) {
        if (!errorResponse.getStatusCode().is4xxClientError()) {
            return unexpected(e);
        }
        HttpStatus status = HttpStatus.valueOf(errorResponse.getStatusCode().value());
        String code = status == HttpStatus.UNSUPPORTED_MEDIA_TYPE ? "UNSUPPORTED_MEDIA_TYPE" : "INVALID_REQUEST";
        return respond(status, code, status.getReasonPhrase());
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiError> unexpected(Exception e) {
        log.error("Unexpected error", e);
        return respond(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "Internal server error");
    }

    private static ResponseEntity<ApiError> respond(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).body(ApiError.of(code, message));
    }
}
