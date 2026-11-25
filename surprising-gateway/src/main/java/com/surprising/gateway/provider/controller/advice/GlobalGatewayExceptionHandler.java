package com.surprising.gateway.provider.controller.advice;

import com.surprising.gateway.c2c.controller.advice.C2cHttpErrors;
import com.surprising.gateway.c2c.exception.C2cProblem;
import com.surprising.gateway.provider.announcement.exception.AnnouncementProblem;
import com.surprising.gateway.provider.auth.client.GoogleIdentityClient;
import com.surprising.gateway.provider.auth.service.LoginVerificationService;
import com.surprising.gateway.provider.exception.KycDocumentStorageException;
import com.surprising.gateway.provider.exception.ProductUnavailableException;
import com.surprising.gateway.provider.legal.exception.LegalProblem;
import com.surprising.gateway.provider.service.GatewayRequestLimits.Rejection;
import com.surprising.realtime.api.exception.ReadViewUnavailableException;
import com.surprising.trading.order.exception.OrderCommandRejectedException;
import com.surprising.trading.order.repository.ProjectionReadResult;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.server.ResponseStatusException;

/**
 * 统一网关全局异常处理，收拢限流、订单拒绝、产品不可用、读视图不可用、公告、法务、C2C、返佣及登录异常映射。
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class GlobalGatewayExceptionHandler {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(GlobalGatewayExceptionHandler.class);

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

    @ModelAttribute
    public void c2cNoStore(HttpServletRequest request, HttpServletResponse response) {
        String uri = request.getRequestURI();
        if (uri != null && (uri.startsWith("/api/v1/c2c") || uri.startsWith("/api/v1/admin/c2c"))) {
            response.setHeader("Cache-Control", "no-store");
            response.setHeader("Pragma", "no-cache");
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

    @ExceptionHandler(com.surprising.gateway.provider.auth.exception.ApiKeyAccessBlockedException.class)
    public ProblemDetail apiKeyBlocked(com.surprising.gateway.provider.auth.exception.ApiKeyAccessBlockedException failure) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, failure.getMessage());
    }

    @ExceptionHandler(OrderCommandRejectedException.class)
    public ProblemDetail orderRejected(OrderCommandRejectedException failure) {
        ProblemDetail body = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, failure.getMessage());
        body.setProperty("code", failure.code().name());
        return body;
    }

    @ExceptionHandler(ProductUnavailableException.class)
    public Object productUnavailable(ProductUnavailableException failure, HandlerMethod handler) {
        if (isC2c(handler)) {
            return C2cHttpErrors.handle(failure);
        }
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, failure.getMessage());
    }

    @ExceptionHandler(ReadViewUnavailableException.class)
    public Object readViewUnavailable(ReadViewUnavailableException failure, HandlerMethod handler,
                                      HttpServletResponse response) throws IOException {
        if (isC2c(handler)) {
            return C2cHttpErrors.handle(failure);
        }
        log.warn("503 read-view unavailable: message={}", failure.getMessage());
        response.sendError(HttpStatus.SERVICE_UNAVAILABLE.value(), failure.getMessage());
        return null;
    }

    @ExceptionHandler(C2cProblem.class)
    public ResponseEntity<Map<String, String>> c2cProblem(C2cProblem problem) {
        return C2cHttpErrors.handle(problem);
    }

    @ExceptionHandler({ArithmeticException.class, HttpMessageNotReadableException.class,
            MethodArgumentTypeMismatchException.class, DataIntegrityViolationException.class,
            DataAccessException.class, KycDocumentStorageException.class, MaxUploadSizeExceededException.class})
    public ResponseEntity<?> dataOrUploadException(RuntimeException failure, HandlerMethod handler) throws Exception {
        if (isC2c(handler)) {
            return C2cHttpErrors.handle(failure);
        }
        if (isAffiliate(handler) && (failure instanceof QueryTimeoutException
                || failure instanceof DataAccessResourceFailureException)) {
            return affiliateUnavailable();
        }
        throw failure;
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
    public ResponseEntity<Map<String, String>> illegalArgument(IllegalArgumentException failure, HandlerMethod handler) {
        if (isC2c(handler)) {
            return C2cHttpErrors.handle(failure);
        }
        if (isAffiliate(handler)) {
            return ResponseEntity.badRequest().body(Map.of("message",
                    failure.getMessage() == null ? "" : failure.getMessage()));
        }
        return ResponseEntity.badRequest().cacheControl(CacheControl.noStore())
                .body(Map.of("message", failure.getMessage() == null ? "" : failure.getMessage()));
    }

    @ExceptionHandler(ProjectionReadResult.ResponseTooLargeException.class)
    public ProblemDetail projectionTooLarge(ProjectionReadResult.ResponseTooLargeException failure) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.PAYLOAD_TOO_LARGE, failure.getMessage());
    }

    @ExceptionHandler(StatusException.class)
    public ResponseEntity<Map<String, String>> statusException(StatusException failure) {
        return ResponseEntity.status(failure.status()).cacheControl(CacheControl.noStore())
                .body(Map.of("message", failure.getMessage() == null ? "" : failure.getMessage()));
    }

    @ExceptionHandler(ResponseStatusException.class)
    public Object responseStatusException(ResponseStatusException failure, HandlerMethod handler) {
        if (isAffiliate(handler)) {
            return ResponseEntity.status(failure.getStatusCode()).body(Map.of("message",
                    failure.getReason() == null ? "返佣请求暂不可用，请稍后重试" : failure.getReason()));
        }
        if (isC2c(handler)) {
            return C2cHttpErrors.handle(failure);
        }
        throw failure;
    }

    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<?> fallbackRuntime(RuntimeException failure, HandlerMethod handler) {
        if (isC2c(handler)) {
            return C2cHttpErrors.handle(failure);
        }
        if (isAffiliate(handler) && failure instanceof IllegalStateException) {
            return affiliateUnavailable();
        }
        throw failure;
    }

    private static ResponseEntity<Map<String, String>> affiliateUnavailable() {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(Map.of("message", "返佣资料暂不可用，请稍后刷新或联系平台核对"));
    }

    private static boolean isC2c(HandlerMethod handler) {
        return handler != null && handler.getBeanType().getPackageName().startsWith("com.surprising.gateway.c2c");
    }

    private static boolean isAffiliate(HandlerMethod handler) {
        return handler != null && handler.getBeanType().getPackageName().startsWith("com.surprising.affiliate.controller");
    }
}
