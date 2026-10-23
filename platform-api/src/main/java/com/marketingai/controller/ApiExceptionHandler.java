package com.marketingai.controller;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;

/**
 * Global exception → RFC 7807 ProblemDetail mapper (audit F-5).
 *
 * Spring's default error handling serialises different exception types in inconsistent shapes.
 * This advice ensures every unhandled exception surfaces as a structured {@link ProblemDetail}
 * (Content-Type: application/problem+json), making API errors predictable for callers.
 *
 * Order: most-specific handlers first; the catch-all last.
 */
@Slf4j
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final URI BLANK = URI.create("about:blank");

    /** ResponseStatusException already carries a status + reason — lift them directly. */
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ProblemDetail> handleResponseStatus(ResponseStatusException ex) {
        ProblemDetail pd = ProblemDetail.forStatus(ex.getStatusCode());
        pd.setType(BLANK);
        String detail = ex.getReason();
        if (detail == null || detail.isBlank()) detail = ex.getMessage();
        pd.setDetail(detail);
        return ResponseEntity.status(ex.getStatusCode()).body(pd);
    }

    /** Spring Security access denied — 403 without leaking role names. */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ProblemDetail> handleAccessDenied(AccessDeniedException ex) {
        ProblemDetail pd = ProblemDetail.forStatus(HttpStatus.FORBIDDEN);
        pd.setType(BLANK);
        pd.setDetail("You do not have permission to perform this action.");
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(pd);
    }

    /** Missing required query / path parameter. */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ProblemDetail> handleMissingParam(MissingServletRequestParameterException ex) {
        ProblemDetail pd = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        pd.setType(BLANK);
        pd.setDetail("Required parameter '" + ex.getParameterName() + "' is missing.");
        return ResponseEntity.badRequest().body(pd);
    }

    /** Type mismatch on path variable or request parameter (e.g. non-UUID). */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ProblemDetail> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        ProblemDetail pd = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        pd.setType(BLANK);
        pd.setDetail("Parameter '" + ex.getName() + "' has an invalid value.");
        return ResponseEntity.badRequest().body(pd);
    }

    /** IllegalArgumentException from service / validation layer — 400 with the supplied message. */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ProblemDetail> handleIllegalArgument(IllegalArgumentException ex) {
        ProblemDetail pd = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        pd.setType(BLANK);
        pd.setDetail(ex.getMessage());
        return ResponseEntity.badRequest().body(pd);
    }

    /** Catch-all: log unexpected exceptions and return 500 without leaking stack-trace details. */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> handleUnexpected(Exception ex) {
        log.error("Unhandled exception: {}", ex.getMessage(), ex);
        ProblemDetail pd = ProblemDetail.forStatus(HttpStatus.INTERNAL_SERVER_ERROR);
        pd.setType(BLANK);
        pd.setDetail("An unexpected error occurred. Please try again later.");
        return ResponseEntity.internalServerError().body(pd);
    }
}
