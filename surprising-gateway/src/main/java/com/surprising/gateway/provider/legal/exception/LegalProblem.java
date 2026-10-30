package com.surprising.gateway.provider.legal.exception;

import org.springframework.http.HttpStatus;

public final class LegalProblem extends RuntimeException {
    public enum Kind {
        INVALID(HttpStatus.BAD_REQUEST), MISSING(HttpStatus.NOT_FOUND), CONFLICT(HttpStatus.CONFLICT),
        NOT_READY(HttpStatus.SERVICE_UNAVAILABLE), CONSENT_REQUIRED(HttpStatus.PRECONDITION_REQUIRED),
        AUTH_REQUIRED(HttpStatus.UNAUTHORIZED), ACCESS_DENIED(HttpStatus.FORBIDDEN);
        private final HttpStatus status;
        Kind(HttpStatus status) { this.status=status; }
        public HttpStatus status() { return status; }
    }
    private final Kind kind;
    public LegalProblem(Kind kind,String message) { super(message);this.kind=kind; }
    public Kind kind() { return kind; }
}
