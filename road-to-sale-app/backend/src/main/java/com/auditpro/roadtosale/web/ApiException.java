package com.auditpro.roadtosale.web;

import org.springframework.http.HttpStatus;

/**
 * Base for domain errors that map to an HTTP status + message in the {@link ApiResponse}
 * envelope. Subtypes pick the status; the {@code GlobalExceptionHandler} renders them.
 */
public class ApiException extends RuntimeException {

    private final HttpStatus status;

    public ApiException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    public HttpStatus getStatus() {
        return status;
    }

    /** 404 — also used for cross-tenant access (do not leak existence). */
    public static class NotFound extends ApiException {
        public NotFound(String message) {
            super(HttpStatus.NOT_FOUND, message);
        }
    }

    /** 409 — conflicting state (e.g. submitting/appending to a COMPLETED session). */
    public static class Conflict extends ApiException {
        public Conflict(String message) {
            super(HttpStatus.CONFLICT, message);
        }
    }

    /** 401 — bad credentials or invalid/expired token. */
    public static class Unauthorized extends ApiException {
        public Unauthorized(String message) {
            super(HttpStatus.UNAUTHORIZED, message);
        }
    }

    /** 400 — invalid request the bean-validation layer didn't catch. */
    public static class BadRequest extends ApiException {
        public BadRequest(String message) {
            super(HttpStatus.BAD_REQUEST, message);
        }
    }
}
