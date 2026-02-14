package com.factbus.api;

import com.factbus.contract.ContractViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Unified error response handler (Step 9).
 *
 * All errors follow the machine-readable format:
 * {
 *   "error_code": "CONTRACT_VIOLATION",
 *   "message": "...",
 *   "timestamp": "2026-..."
 * }
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(ContractViolationException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, Object> handleContractViolation(ContractViolationException ex) {
        log.warn("Contract violation: {}", ex.getMessage());
        return errorResponse("CONTRACT_VIOLATION", ex.getMessage());
    }

    @ExceptionHandler(DuplicateEventException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public Map<String, Object> handleDuplicateEvent(DuplicateEventException ex) {
        log.warn("Duplicate event: {}", ex.getMessage());
        return errorResponse("DUPLICATE_EVENT", ex.getMessage());
    }

    @ExceptionHandler({
        MethodArgumentTypeMismatchException.class,
        HttpMessageNotReadableException.class
    })
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, Object> handleParseErrors(Exception ex) {
        return errorResponse("BAD_REQUEST", "request format is invalid: " + ex.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, Object> handleIllegalArgument(IllegalArgumentException ex) {
        return errorResponse("INVALID_ARGUMENT", ex.getMessage());
    }

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public Map<String, Object> handleUnexpected(Exception ex) {
        log.error("Unexpected error", ex);
        return errorResponse("INTERNAL_ERROR", "an unexpected error occurred");
    }

    private Map<String, Object> errorResponse(String errorCode, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error_code", errorCode);
        body.put("message", message);
        body.put("timestamp", Instant.now().toString());
        return body;
    }
}
