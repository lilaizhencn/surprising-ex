package com.surprising.gateway.provider.controller.advice;

import com.surprising.gateway.provider.announcement.exception.AnnouncementProblem;
import com.surprising.gateway.provider.auth.client.GoogleIdentityClient;
import com.surprising.gateway.provider.auth.service.LoginVerificationService;
import com.surprising.gateway.provider.exception.ProductUnavailableException;
import com.surprising.gateway.provider.legal.exception.LegalProblem;
import com.surprising.gateway.provider.service.GatewayRequestLimits.Rejection;
import com.surprising.realtime.api.exception.ReadViewUnavailableException;
import com.surprising.trading.order.exception.OrderCommandRejectedException;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 统一网关全局异常处理，收拢限流、订单拒绝、产品不可用、读视图不可用、公告、法务及登录异常映射。
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class GlobalGatewayExceptionHandler {

    public static class StatusException extends RuntimeException {
        private final int status;

        public StatusException(int status, String message) {
            super(message);
            this.status = status;
        }

        public int status() {
            return status;
        }
    }

    @ExceptionHandler(Rejection.class)
    public ResponseEntity<Map<String, Object>> rateLimited(Rejection failure) {
        return limitResponse(failure);
    }

    @ExceptionHandler(tools.jackson.core.exc.StreamConstraintsException.class)
    public ResponseEntity<Map<String, Object>> oversizedJson() {
        return ResponseEntity.badRequest().cacheControl(CacheControl.noStore())
                .body(Map.of("code", "INVALID_REQUEST", "message", "Request JSON exceeds parser limits"));
    }

    public static ResponseEntity<Map<String, Object>> limitResponse(Rejection failure) {
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

    @ExceptionHandler(OrderCommandRejectedException.class)
    public ProblemDetail orderRejected(OrderCommandRejectedException failure) {
        ProblemDetail body = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, failure.getMessage());
        body.setProperty("code", failure.code().name());
        return body;
    }

    @ExceptionHandler(ProductUnavailableException.class)
    public ProblemDetail productUnavailable(ProductUnavailableException failure) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, failure.getMessage());
    }

    @ExceptionHandler(ReadViewUnavailableException.class)
    public void readViewUnavailable(ReadViewUnavailableException failure, HttpServletResponse response)
            throws IOException {
        response.sendError(HttpStatus.SERVICE_UNAVAILABLE.value(), failure.getMessage());
    }

    @ExceptionHandler(AnnouncementProblem.class)
    public ResponseEntity<Map<String, Object>> announcementProblem(AnnouncementProblem problem) {
        int status = switch (problem.kind()) {
            case INVALID_STATE -> 400;
            case MISSING -> 404;
            case CONFLICT -> 409;
            case AUTH_REQUIRED -> 401;
            case ACCESS_DENIED -> 403;
        };
        return ResponseEntity.status(status).body(Map.of("status", status, "message", problem.getMessage()));
    }

    @ExceptionHandler(LegalProblem.class)
    public ResponseEntity<Map<String, String>> legalProblem(LegalProblem problem) {
        return ResponseEntity.status(problem.kind().status())
                .body(Map.of("code", "LEGAL_" + problem.kind().name(), "message", problem.getMessage()));
    }

    @ExceptionHandler(LoginVerificationService.VerificationFailure.class)
    public ResponseEntity<Map<String, String>> verificationFailure(LoginVerificationService.VerificationFailure failure) {
        String msg = failure.getMessage() == null ? "" : failure.getMessage();
        int status = msg.contains("RATE_LIMITED") ? 429
                : msg.contains("UNAVAILABLE") || msg.contains("DELIVERY_FAILED") ? 503 : 400;
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(Map.of("message", msg));
    }

    @ExceptionHandler(GoogleIdentityClient.Unavailable.class)
    public ResponseEntity<Map<String, String>> googleUnavailable() {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).cacheControl(CacheControl.noStore())
                .body(Map.of("message", "Google sign-in is temporarily unavailable"));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> illegalArgument(IllegalArgumentException failure) {
        return ResponseEntity.badRequest().cacheControl(CacheControl.noStore())
                .body(Map.of("message", failure.getMessage() == null ? "" : failure.getMessage()));
    }

    @ExceptionHandler(StatusException.class)
    public ResponseEntity<Map<String, String>> statusException(StatusException failure) {
        return ResponseEntity.status(failure.status()).cacheControl(CacheControl.noStore())
                .body(Map.of("message", failure.getMessage() == null ? "" : failure.getMessage()));
    }
}
