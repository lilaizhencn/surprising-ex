package com.surprising.gateway.provider.controller;

import com.surprising.gateway.provider.service.GatewayRequestLimits.Rejection;
import java.util.Map;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class GatewayLimitErrors {
    @ExceptionHandler(Rejection.class)
    public ResponseEntity<Map<String, Object>> rejected(Rejection failure) { return response(failure); }

    @ExceptionHandler(tools.jackson.core.exc.StreamConstraintsException.class)
    public ResponseEntity<Map<String, Object>> oversizedJson() {
        return ResponseEntity.badRequest().cacheControl(CacheControl.noStore())
                .body(Map.of("code", "INVALID_REQUEST", "message", "Request JSON exceeds parser limits"));
    }

    public static ResponseEntity<Map<String, Object>> response(Rejection failure) {
        HttpStatus status = switch (failure.reason()) {
            case RATE_LIMITED, WEBSOCKET_CONNECTION_LIMIT -> HttpStatus.TOO_MANY_REQUESTS;
            case REQUEST_EXCEEDS_RATE_BURST -> HttpStatus.BAD_REQUEST;
            case GATEWAY_BUSY, RATE_LIMIT_STORAGE_UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
        };
        var response = ResponseEntity.status(status).cacheControl(CacheControl.noStore());
        if (status == HttpStatus.BAD_REQUEST)
            return response.body(Map.of("code", failure.reason().name(), "group", failure.group(),
                    "message", "Reduce the requested batch or response size"));
        return response.header(HttpHeaders.RETRY_AFTER, Long.toString(failure.retryAfterSeconds()))
                .body(Map.of("code", failure.reason().name(), "group", failure.group(),
                        "retryAfterSeconds", failure.retryAfterSeconds()));
    }
}
