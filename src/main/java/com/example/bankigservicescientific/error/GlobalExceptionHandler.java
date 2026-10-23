package com.example.bankigservicescientific.error;

import com.example.bankigservicescientific.account.AccountNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
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

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiError> unexpected(Exception e) {
        log.error("Unexpected error", e);
        return respond(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "Internal server error");
    }

    private static ResponseEntity<ApiError> respond(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).body(ApiError.of(code, message));
    }
}
