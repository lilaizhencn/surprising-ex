package com.surprising.gateway.provider.controller.advice;

import com.surprising.gateway.provider.announcement.exception.AnnouncementProblem;
import com.surprising.gateway.provider.exception.ProductUnavailableException;
import com.surprising.gateway.provider.legal.exception.LegalProblem;
import com.surprising.realtime.api.exception.ReadViewUnavailableException;
import com.surprising.trading.order.exception.OrderCommandRejectedException;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 统一网关全局异常处理，收拢订单拒绝、产品不可用、读视图不可用、公告及法务异常映射。
 */
@RestControllerAdvice
@Order(Ordered.LOWEST_PRECEDENCE - 10)
public class GlobalGatewayExceptionHandler {

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
}
